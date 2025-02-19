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

package org.elasticsearch.cluster;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexGraveyard;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.gateway.GatewayService.STATE_NOT_RECOVERED_BLOCK;
import static org.elasticsearch.test.ESIntegTestCase.Scope.TEST;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;

/**
 * This test suite sets up a situation where the cluster has two plugins installed (node, and node-and-transport-client), and a transport
 * client only has node-and-transport-client plugin installed. Each of these plugins inject customs into the cluster state and we want to
 * check that the client can de-serialize a cluster state response based on the fact that the response should not contain customs that the
 * transport client does not understand based on the fact that it only presents the node-and-transport-client-feature.
 */
@ESIntegTestCase.ClusterScope(scope = TEST)
public class ClusterStateIT extends ESIntegTestCase {

    public abstract static
    class Custom implements MetaData.Custom {

        private static final ParseField VALUE = new ParseField("value");

        private final int value;

        int value() {
            return value;
        }

        Custom(final int value) {
            this.value = value;
        }

        Custom(final StreamInput in) throws IOException {
            value = in.readInt();
        }

        @Override
        public EnumSet<MetaData.XContentContext> context() {
            return MetaData.ALL_CONTEXTS;
        }

        @Override
        public Diff<MetaData.Custom> diff(final MetaData.Custom previousState) {
            return null;
        }

        @Override
        public void writeTo(final StreamOutput out) throws IOException {
            out.writeInt(value);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(VALUE.getPreferredName(), value);
            return builder;
        }

    }

    public static class NodeCustom extends Custom {

        public static final String TYPE = "node";

        NodeCustom(final int value) {
            super(value);
        }

        NodeCustom(final StreamInput in) throws IOException {
            super(in);
        }

        @Override
        public String getWriteableName() {
            return TYPE;
        }

        @Override
        public Version getMinimalSupportedVersion() {
            return Version.CURRENT;
        }

        @Override
        public Optional<String> getRequiredFeature() {
            return Optional.of("node");
        }

    }

    public static class NodeAndTransportClientCustom extends Custom {

        public static final String TYPE = "node-and-transport-client";

        NodeAndTransportClientCustom(final int value) {
            super(value);
        }

        public NodeAndTransportClientCustom(final StreamInput in) throws IOException {
            super(in);
        }

        @Override
        public String getWriteableName() {
            return TYPE;
        }

        @Override
        public Version getMinimalSupportedVersion() {
            return Version.CURRENT;
        }

        /*
         * This custom should always be returned yet we randomize whether it has a required feature that the client is expected to have
         * versus not requiring any feature. We use a field to make the random choice exactly once.
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private final Optional<String> requiredFeature = randomBoolean() ? Optional.empty() : Optional.of("node-and-transport-client");

        @Override
        public Optional<String> getRequiredFeature() {
            return requiredFeature;
        }

    }

    public abstract static class CustomPlugin extends Plugin {

        private final List<NamedWriteableRegistry.Entry> namedWritables = new ArrayList<>();
        private final List<NamedXContentRegistry.Entry> namedXContents = new ArrayList<>();

        public CustomPlugin() {
            registerBuiltinWritables();
        }

        protected <T extends MetaData.Custom> void registerMetaDataCustom(
                final String name, final Writeable.Reader<T> reader, final CheckedFunction<XContentParser, T, IOException> parser) {
            namedWritables.add(new NamedWriteableRegistry.Entry(MetaData.Custom.class, name, reader));
            namedXContents.add(new NamedXContentRegistry.Entry(MetaData.Custom.class, new ParseField(name), parser));
        }

        protected abstract void registerBuiltinWritables();

        protected abstract String getType();

        protected abstract Custom getInstance();

        @Override
        public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
            return namedWritables;
        }

        @Override
        public List<NamedXContentRegistry.Entry> getNamedXContent() {
            return namedXContents;
        }

        private final AtomicBoolean installed = new AtomicBoolean();

        @Override
        public Collection<Object> createComponents(
            final Client client,
            final ClusterService clusterService,
            final ThreadPool threadPool,
            final ResourceWatcherService resourceWatcherService,
            final ScriptService scriptService,
            final NamedXContentRegistry xContentRegistry,
            final Environment environment,
            final NodeEnvironment nodeEnvironment,
            final NamedWriteableRegistry namedWriteableRegistry,
            final IndexNameExpressionResolver indexNameExpressionResolver) {
            clusterService.addListener(event -> {
                final ClusterState state = event.state();
                if (state.getBlocks().hasGlobalBlock(STATE_NOT_RECOVERED_BLOCK)) {
                    return;
                }

                final MetaData metaData = state.metaData();
                if (state.nodes().isLocalNodeElectedMaster()) {
                    if (metaData.custom(getType()) == null) {
                        if (installed.compareAndSet(false, true)) {
                            clusterService.submitStateUpdateTask("install-metadata-custom", new ClusterStateUpdateTask(Priority.URGENT) {

                                @Override
                                public ClusterState execute(ClusterState currentState) {
                                    if (currentState.custom(getType()) == null) {
                                        final MetaData.Builder builder = MetaData.builder(currentState.metaData());
                                        builder.putCustom(getType(), getInstance());
                                        return ClusterState.builder(currentState).metaData(builder).build();
                                    } else {
                                        return currentState;
                                    }
                                }

                                @Override
                                public void onFailure(String source, Exception e) {
                                    throw new AssertionError(e);
                                }

                            });
                        }
                    }
                }

            });
            return Collections.emptyList();
        }
    }

    public static class NodePlugin extends CustomPlugin {

        public Optional<String> getFeature() {
            return Optional.of("node");
        }

        static final int VALUE = randomInt();

        @Override
        protected void registerBuiltinWritables() {
            registerMetaDataCustom(
                    NodeCustom.TYPE,
                    NodeCustom::new,
                    parser -> {
                        throw new IOException(new UnsupportedOperationException());
                    });
        }

        @Override
        protected String getType() {
            return NodeCustom.TYPE;
        }

        @Override
        protected Custom getInstance() {
            return new NodeCustom(VALUE);
        }

    }

    public static class NodeAndTransportClientPlugin extends CustomPlugin {

        @Override
        protected Optional<String> getFeature() {
            return Optional.of("node-and-transport-client");
        }

        static final int VALUE = randomInt();

        @Override
        protected void registerBuiltinWritables() {
            registerMetaDataCustom(
                    NodeAndTransportClientCustom.TYPE,
                    NodeAndTransportClientCustom::new,
                    parser -> {
                        throw new IOException(new UnsupportedOperationException());
                    });
        }

        @Override
        protected String getType() {
            return NodeAndTransportClientCustom.TYPE;
        }

        @Override
        protected Custom getInstance() {
            return new NodeAndTransportClientCustom(VALUE);
        }

    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(NodePlugin.class, NodeAndTransportClientPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return Collections.singletonList(NodeAndTransportClientPlugin.class);
    }

    public void testOptionalCustoms() throws Exception {
        // ensure that the customs are injected into the cluster state
        assertBusy(() -> assertTrue(clusterService().state().metaData().customs().containsKey(NodeCustom.TYPE)));
        assertBusy(() -> assertTrue(clusterService().state().metaData().customs().containsKey(NodeAndTransportClientCustom.TYPE)));
        final ClusterStateResponse state = internalCluster().transportClient().admin().cluster().prepareState().get();
        final ImmutableOpenMap<String, MetaData.Custom> customs = state.getState().metaData().customs();
        final Set<String> keys = new HashSet<>(Arrays.asList(customs.keys().toArray(String.class)));
        assertThat(keys, hasItem(IndexGraveyard.TYPE));
        assertThat(keys, not(hasItem(NodeCustom.TYPE)));
        assertThat(keys, hasItem(NodeAndTransportClientCustom.TYPE));
        final MetaData.Custom actual = customs.get(NodeAndTransportClientCustom.TYPE);
        assertThat(actual, instanceOf(NodeAndTransportClientCustom.class));
        assertThat(((NodeAndTransportClientCustom)actual).value(), equalTo(NodeAndTransportClientPlugin.VALUE));
    }

}
