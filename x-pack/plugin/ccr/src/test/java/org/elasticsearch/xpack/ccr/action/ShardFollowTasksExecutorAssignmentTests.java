/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr.action;

import org.elasticsearch.Version;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData.Assignment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.ccr.CcrSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ShardFollowTasksExecutorAssignmentTests extends ESTestCase {

    public void testAssignmentToNodeWithDataAndRemoteClusterClientRoles() {
        runAssignmentTest(
            new HashSet<>(Arrays.asList(DiscoveryNodeRole.DATA_ROLE, DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE)),
            randomIntBetween(0, 8),
            () -> new HashSet<>(randomSubsetOf(new HashSet<>(Arrays.asList(DiscoveryNodeRole.DATA_ROLE, DiscoveryNodeRole.INGEST_ROLE)))),
            (theSpecial, assignment) -> {
                assertTrue(assignment.isAssigned());
                assertThat(assignment.getExecutorNode(), equalTo(theSpecial.getId()));
            }
        );
    }

    public void testDataRoleWithoutRemoteClusterServiceRole() {
        runNoAssignmentTest(Collections.singleton(DiscoveryNodeRole.DATA_ROLE));
    }

    public void testRemoteClusterClientRoleWithoutDataRole() {
        runNoAssignmentTest(Collections.singleton(DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE));
    }

    private void runNoAssignmentTest(final Set<DiscoveryNodeRole> roles) {
        runAssignmentTest(
            roles,
            0,
            Collections::emptySet,
            (theSpecial, assignment) -> {
                assertFalse(assignment.isAssigned());
                assertThat(assignment.getExplanation(), equalTo("no nodes found with data and remote cluster client roles"));
            }
        );
    }

    private void runAssignmentTest(
        final Set<DiscoveryNodeRole> theSpecialRoles,
        final int numberOfOtherNodes,
        final Supplier<Set<DiscoveryNodeRole>> otherNodesRolesSupplier,
        final BiConsumer<DiscoveryNode, Assignment> consumer
    ) {
        final ClusterService clusterService = mock(ClusterService.class);
        when(clusterService.getClusterSettings())
            .thenReturn(new ClusterSettings(Settings.EMPTY, Collections.singleton(CcrSettings.CCR_WAIT_FOR_METADATA_TIMEOUT)));
        final SettingsModule settingsModule = mock(SettingsModule.class);
        when(settingsModule.getSettings()).thenReturn(Settings.EMPTY);
        final ShardFollowTasksExecutor executor =
            new ShardFollowTasksExecutor(mock(Client.class), mock(ThreadPool.class), clusterService, settingsModule);
        final ClusterState.Builder clusterStateBuilder = ClusterState.builder(new ClusterName("test"));
        final DiscoveryNodes.Builder nodesBuilder = DiscoveryNodes.builder();
        final DiscoveryNode theSpecial = newNode(theSpecialRoles);
        nodesBuilder.add(theSpecial);
        for (int i = 0; i < numberOfOtherNodes; i++) {
            nodesBuilder.add(newNode(otherNodesRolesSupplier.get()));
        }
        clusterStateBuilder.nodes(nodesBuilder);
        final Assignment assignment = executor.getAssignment(mock(ShardFollowTask.class), clusterStateBuilder.build());
        consumer.accept(theSpecial, assignment);
    }

    private static DiscoveryNode newNode(final Set<DiscoveryNodeRole> roles) {
        return new DiscoveryNode(
            "node_" + UUIDs.randomBase64UUID(random()),
            buildNewFakeTransportAddress(),
            Collections.emptyMap(),
            roles,
            Version.CURRENT
        );
    }

}
