/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.transport;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class RemoteConnectionManager implements ConnectionManager {

    private final String clusterAlias;
    private final ConnectionManager delegate;
    private final AtomicLong counter = new AtomicLong();
    private volatile List<Transport.Connection> connections = Collections.emptyList();

    RemoteConnectionManager(String clusterAlias, ConnectionManager delegate) {
        this.clusterAlias = clusterAlias;
        this.delegate = delegate;
        this.delegate.addListener(new TransportConnectionListener() {
            @Override
            public void onNodeConnected(DiscoveryNode node, Transport.Connection connection) {
                addConnection(connection);
            }

            @Override
            public void onNodeDisconnected(DiscoveryNode node, Transport.Connection connection) {
                removeConnection(connection);
            }
        });
    }

    @Override
    public void connectToNode(DiscoveryNode node, ConnectionProfile connectionProfile,
                              ConnectionManager.ConnectionValidator connectionValidator,
                              ActionListener<Void> listener) throws ConnectTransportException {
        delegate.connectToNode(node, connectionProfile, connectionValidator, listener);
    }

    @Override
    public void addListener(TransportConnectionListener listener) {
        delegate.addListener(listener);
    }

    @Override
    public void removeListener(TransportConnectionListener listener) {
        delegate.removeListener(listener);
    }

    @Override
    public void openConnection(DiscoveryNode node, ConnectionProfile profile, ActionListener<Transport.Connection> listener) {
        delegate.openConnection(node, profile, listener);
    }

    @Override
    public Transport.Connection getConnection(DiscoveryNode node) {
        try {
            return delegate.getConnection(node);
        } catch (NodeNotConnectedException e) {
            return new ProxyConnection(getAnyRemoteConnection(), node);
        }
    }

    @Override
    public boolean nodeConnected(DiscoveryNode node) {
        return delegate.nodeConnected(node);
    }

    @Override
    public void disconnectFromNode(DiscoveryNode node) {
        delegate.disconnectFromNode(node);
    }

    @Override
    public ConnectionProfile getConnectionProfile() {
        return delegate.getConnectionProfile();
    }

    public Transport.Connection getAnyRemoteConnection() {
        List<Transport.Connection> localConnections = this.connections;
        if (localConnections.isEmpty()) {
            throw new NoSuchRemoteClusterException(clusterAlias);
        } else {
            long curr;
            while ((curr = counter.incrementAndGet()) == Long.MIN_VALUE);
            return localConnections.get(Math.toIntExact(Math.floorMod(curr, (long) localConnections.size())));
        }
    }

    @Override
    public int size() {
        // Although we use a delegate instance, we report the connection manager size based on the
        // RemoteConnectionManager's knowledge of the connections. This is because there is a brief window
        // in between the time when the connection is added to the delegate map, and the time when
        // nodeConnected is called.
        return this.connections.size();
    }

    @Override
    public void close() {
        delegate.closeNoBlock();
    }

    @Override
    public void closeNoBlock() {
        delegate.closeNoBlock();
    }

    private synchronized void addConnection(Transport.Connection addedConnection) {
        ArrayList<Transport.Connection> newConnections = new ArrayList<>(this.connections);
        newConnections.add(addedConnection);
        this.connections = Collections.unmodifiableList(newConnections);
    }

    private synchronized void removeConnection(Transport.Connection removedConnection) {
        int newSize = this.connections.size() - 1;
        ArrayList<Transport.Connection> newConnections = new ArrayList<>(newSize);
        for (Transport.Connection connection : this.connections) {
            if (connection.equals(removedConnection) == false) {
                newConnections.add(connection);
            }
        }
        assert newConnections.size() == newSize : "Expected connection count: " + newSize + ", Found: " + newConnections.size();
        this.connections = Collections.unmodifiableList(newConnections);
    }

    static final class ProxyConnection implements Transport.Connection {
        private final Transport.Connection connection;
        private final DiscoveryNode targetNode;

        private ProxyConnection(Transport.Connection connection, DiscoveryNode targetNode) {
            this.connection = connection;
            this.targetNode = targetNode;
        }

        @Override
        public DiscoveryNode getNode() {
            return targetNode;
        }

        @Override
        public void sendRequest(long requestId, String action, TransportRequest request, TransportRequestOptions options)
            throws IOException, TransportException {
            connection.sendRequest(requestId, TransportActionProxy.getProxyAction(action),
                TransportActionProxy.wrapRequest(targetNode, request), options);
        }

        @Override
        public void close() {
            assert false: "proxy connections must not be closed";
        }

        @Override
        public void addCloseListener(ActionListener<Void> listener) {
            connection.addCloseListener(listener);
        }

        @Override
        public boolean isClosed() {
            return connection.isClosed();
        }

        @Override
        public Version getVersion() {
            return connection.getVersion();
        }
    }
}
