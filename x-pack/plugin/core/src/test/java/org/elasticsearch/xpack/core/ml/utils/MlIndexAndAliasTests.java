/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml.utils;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesAction;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest.AliasActions;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static org.elasticsearch.xpack.core.ml.utils.MlIndexAndAlias.HIDDEN_INTRODUCED_VERSION;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class MlIndexAndAliasTests extends ESTestCase {

    private static final String TEST_INDEX_PREFIX = "test";
    private static final String TEST_INDEX_ALIAS = "test-alias";
    private static final String LEGACY_INDEX_WITHOUT_SUFFIX = TEST_INDEX_PREFIX;
    private static final String FIRST_CONCRETE_INDEX = "test-000001";

    private ThreadPool threadPool;
    private IndicesAdminClient indicesAdminClient;
    private AdminClient adminClient;
    private Client client;
    private ActionListener<Boolean> listener;

    private ArgumentCaptor<CreateIndexRequest> createRequestCaptor;
    private ArgumentCaptor<IndicesAliasesRequest> aliasesRequestCaptor;

    @Before
    public void setUpMocks() {
        threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));

        indicesAdminClient = mock(IndicesAdminClient.class);
        when(indicesAdminClient.prepareCreate(FIRST_CONCRETE_INDEX))
            .thenReturn(new CreateIndexRequestBuilder(client, CreateIndexAction.INSTANCE, FIRST_CONCRETE_INDEX));
        doAnswer(withResponse(new CreateIndexResponse(true, true, FIRST_CONCRETE_INDEX))).when(indicesAdminClient).create(any(), any());
        when(indicesAdminClient.prepareAliases()).thenReturn(new IndicesAliasesRequestBuilder(client, IndicesAliasesAction.INSTANCE));
        doAnswer(withResponse(new AcknowledgedResponse(true))).when(indicesAdminClient).aliases(any(), any());

        adminClient = mock(AdminClient.class);
        when(adminClient.indices()).thenReturn(indicesAdminClient);

        client = mock(Client.class);
        when(client.threadPool()).thenReturn(threadPool);
        when(client.admin()).thenReturn(adminClient);

        listener = mock(ActionListener.class);

        createRequestCaptor = ArgumentCaptor.forClass(CreateIndexRequest.class);
        aliasesRequestCaptor = ArgumentCaptor.forClass(IndicesAliasesRequest.class);
    }

    @After
    public void verifyNoMoreInteractionsWithMocks() {
        verifyNoMoreInteractions(indicesAdminClient, listener);
    }

    public void testCreateStateIndexAndAliasIfNecessary_CleanState() {
        ClusterState clusterState = createClusterState(Collections.emptyMap());
        createIndexAndAliasIfNecessary(clusterState);

        InOrder inOrder = inOrder(indicesAdminClient, listener);
        inOrder.verify(indicesAdminClient).prepareCreate(FIRST_CONCRETE_INDEX);
        inOrder.verify(indicesAdminClient).create(createRequestCaptor.capture(), any());
        inOrder.verify(listener).onResponse(true);

        CreateIndexRequest createRequest = createRequestCaptor.getValue();
        assertThat(createRequest.index(), equalTo(FIRST_CONCRETE_INDEX));
        assertThat(createRequest.aliases(), equalTo(Collections.singleton(new Alias(TEST_INDEX_ALIAS).isHidden(true))));
    }

    private void assertNoClientInteractionsWhenWriteAliasAlreadyExists(String indexName) {
        ClusterState clusterState = createClusterState(Collections.singletonMap(indexName, createIndexMetaDataWithAlias(indexName)));
        createIndexAndAliasIfNecessary(clusterState);

        verify(listener).onResponse(false);
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasAlreadyExistsAndPointsAtInitialStateIndex() {
        assertNoClientInteractionsWhenWriteAliasAlreadyExists(FIRST_CONCRETE_INDEX);
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasAlreadyExistsAndPointsAtSubsequentStateIndex() {
        assertNoClientInteractionsWhenWriteAliasAlreadyExists("test-000007");
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasAlreadyExistsAndPointsAtDummyIndex() {
        assertNoClientInteractionsWhenWriteAliasAlreadyExists("dummy-index");
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasAlreadyExistsAndPointsAtLegacyStateIndex() {
        ClusterState clusterState =
            createClusterState(
                Collections.singletonMap(LEGACY_INDEX_WITHOUT_SUFFIX, createIndexMetaDataWithAlias(LEGACY_INDEX_WITHOUT_SUFFIX)));
        createIndexAndAliasIfNecessary(clusterState);

        InOrder inOrder = inOrder(indicesAdminClient, listener);
        inOrder.verify(indicesAdminClient).prepareCreate(FIRST_CONCRETE_INDEX);
        inOrder.verify(indicesAdminClient).create(createRequestCaptor.capture(), any());
        inOrder.verify(indicesAdminClient).prepareAliases();
        inOrder.verify(indicesAdminClient).aliases(aliasesRequestCaptor.capture(), any());
        inOrder.verify(listener).onResponse(true);

        CreateIndexRequest createRequest = createRequestCaptor.getValue();
        assertThat(createRequest.index(), equalTo(FIRST_CONCRETE_INDEX));
        assertThat(createRequest.aliases(), empty());

        IndicesAliasesRequest indicesAliasesRequest = aliasesRequestCaptor.getValue();
        assertThat(
            indicesAliasesRequest.getAliasActions(),
            contains(
                AliasActions.add().alias(TEST_INDEX_ALIAS).index(FIRST_CONCRETE_INDEX).isHidden(true),
                AliasActions.remove().alias(TEST_INDEX_ALIAS).index(LEGACY_INDEX_WITHOUT_SUFFIX)));
    }

    private void assertMlStateWriteAliasAddedToMostRecentMlStateIndex(List<String> existingIndexNames, String expectedWriteIndexName) {
        ClusterState clusterState =
            createClusterState(
                existingIndexNames.stream().collect(toMap(Function.identity(), MlIndexAndAliasTests::createIndexMetaData)));
        createIndexAndAliasIfNecessary(clusterState);

        InOrder inOrder = inOrder(indicesAdminClient, listener);
        inOrder.verify(indicesAdminClient).prepareAliases();
        inOrder.verify(indicesAdminClient).aliases(aliasesRequestCaptor.capture(), any());
        inOrder.verify(listener).onResponse(true);

        IndicesAliasesRequest indicesAliasesRequest = aliasesRequestCaptor.getValue();
        assertThat(
            indicesAliasesRequest.getAliasActions(),
            contains(AliasActions.add().alias(TEST_INDEX_ALIAS).index(expectedWriteIndexName).isHidden(true)));
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasDoesNotExistButInitialStateIndexExists() {
        assertMlStateWriteAliasAddedToMostRecentMlStateIndex(
            Arrays.asList(FIRST_CONCRETE_INDEX), FIRST_CONCRETE_INDEX);
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasDoesNotExistButSubsequentStateIndicesExist() {
        assertMlStateWriteAliasAddedToMostRecentMlStateIndex(
            Arrays.asList("test-000003", "test-000040", "test-000500"), "test-000500");
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasDoesNotExistButBothLegacyAndNewIndicesExist() {
        assertMlStateWriteAliasAddedToMostRecentMlStateIndex(
            Arrays.asList(LEGACY_INDEX_WITHOUT_SUFFIX, "test-000003", "test-000040", "test-000500"), "test-000500");
    }

    public void testCreateStateIndexAndAliasIfNecessary_WriteAliasDoesNotExistButLegacyStateIndexExists() {
        ClusterState clusterState =
            createClusterState(Collections.singletonMap(LEGACY_INDEX_WITHOUT_SUFFIX, createIndexMetaData(LEGACY_INDEX_WITHOUT_SUFFIX)));
        createIndexAndAliasIfNecessary(clusterState);

        InOrder inOrder = inOrder(indicesAdminClient, listener);
        inOrder.verify(indicesAdminClient).prepareCreate(FIRST_CONCRETE_INDEX);
        inOrder.verify(indicesAdminClient).create(createRequestCaptor.capture(), any());
        inOrder.verify(listener).onResponse(true);

        CreateIndexRequest createRequest = createRequestCaptor.getValue();
        assertThat(createRequest.index(), equalTo(FIRST_CONCRETE_INDEX));
        assertThat(createRequest.aliases(), equalTo(Collections.singleton(new Alias(TEST_INDEX_ALIAS).isHidden(true))));
    }

    public void testIndexNameComparator() {
        Comparator<String> comparator = MlIndexAndAlias.INDEX_NAME_COMPARATOR;
        assertThat(
            Stream.of("test-000001").max(comparator).get(),
            equalTo("test-000001"));
        assertThat(
            Stream.of("test-000002", "test-000001").max(comparator).get(),
            equalTo("test-000002"));
        assertThat(
            Stream.of("test-000003", "test-000040", "test-000500").max(comparator).get(),
            equalTo("test-000500"));
        assertThat(
            Stream.of("test-000042", "test-000049", "test-000038").max(comparator).get(),
            equalTo("test-000049"));
        assertThat(
            Stream.of("test", "test-000003", "test-000040", "test-000500").max(comparator).get(),
            equalTo("test-000500"));
        assertThat(
            Stream.of(".reindexed-6-test", "test-000042").max(comparator).get(),
            equalTo("test-000042"));
        assertThat(
            Stream.of(".a-000002", ".b-000001").max(comparator).get(),
            equalTo(".a-000002"));
    }

    private void createIndexAndAliasIfNecessary(ClusterState clusterState) {
        MlIndexAndAlias.createIndexAndAliasIfNecessary(
            client, clusterState, new IndexNameExpressionResolver(), TEST_INDEX_PREFIX, TEST_INDEX_ALIAS, listener);
    }

    @SuppressWarnings("unchecked")
    private static <Response> Answer<Response> withResponse(Response response) {
        return invocationOnMock -> {
            ActionListener<Response> listener = (ActionListener<Response>) invocationOnMock.getArguments()[1];
            listener.onResponse(response);
            return null;
        };
    }

    private static ClusterState createClusterState(Map<String, IndexMetaData> indices) {
        return ClusterState.builder(ClusterName.DEFAULT)
            .metaData(MetaData.builder()
                .indices(ImmutableOpenMap.<String, IndexMetaData>builder().putAll(indices).build()).build())
            .nodes(DiscoveryNodes.builder()
                .add(new DiscoveryNode("", new TransportAddress(InetAddress.getLoopbackAddress(), 9200), HIDDEN_INTRODUCED_VERSION)))
            .build();
    }

    private static IndexMetaData createIndexMetaData(String indexName) {
        return createIndexMetaData(indexName, false);
    }

    private static IndexMetaData createIndexMetaDataWithAlias(String indexName) {
        return createIndexMetaData(indexName, true);
    }

    private static IndexMetaData createIndexMetaData(String indexName, boolean withAlias) {
        Settings settings =
            Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(IndexMetaData.SETTING_INDEX_VERSION_CREATED.getKey(), Version.CURRENT)
                .build();
        IndexMetaData.Builder builder = IndexMetaData.builder(indexName)
            .settings(settings);
        if (withAlias) {
            builder.putAlias(AliasMetaData.builder(TEST_INDEX_ALIAS).build());
        }
        return builder.build();
    }
}
