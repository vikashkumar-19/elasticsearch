/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.transform.transforms;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.elasticsearch.xpack.core.transform.TransformField;
import org.elasticsearch.xpack.core.transform.TransformMessages;
import org.elasticsearch.xpack.core.transform.action.StartTransformAction;
import org.elasticsearch.xpack.core.transform.transforms.TransformCheckpoint;
import org.elasticsearch.xpack.core.transform.transforms.TransformConfig;
import org.elasticsearch.xpack.core.transform.transforms.TransformState;
import org.elasticsearch.xpack.core.transform.transforms.TransformStoredDoc;
import org.elasticsearch.xpack.core.transform.transforms.TransformTaskParams;
import org.elasticsearch.xpack.core.transform.transforms.persistence.TransformInternalIndexConstants;
import org.elasticsearch.xpack.transform.Transform;
import org.elasticsearch.xpack.transform.TransformServices;
import org.elasticsearch.xpack.transform.notifications.TransformAuditor;
import org.elasticsearch.xpack.transform.persistence.SeqNoPrimaryTermAndIndex;
import org.elasticsearch.xpack.transform.persistence.TransformInternalIndex;
import org.elasticsearch.xpack.transform.transforms.pivot.SchemaUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TransformPersistentTasksExecutor extends PersistentTasksExecutor<TransformTaskParams> {

    private static final Logger logger = LogManager.getLogger(TransformPersistentTasksExecutor.class);

    // The amount of time we wait for the cluster state to respond when being marked as failed
    private static final int MARK_AS_FAILED_TIMEOUT_SEC = 90;
    private final Client client;
    private final TransformServices transformServices;
    private final ThreadPool threadPool;
    private final ClusterService clusterService;
    private final IndexNameExpressionResolver resolver;
    private final TransformAuditor auditor;
    private volatile int numFailureRetries;

    public TransformPersistentTasksExecutor(
        Client client,
        TransformServices transformServices,
        ThreadPool threadPool,
        ClusterService clusterService,
        Settings settings,
        IndexNameExpressionResolver resolver
    ) {
        super(TransformField.TASK_NAME, Transform.TASK_THREAD_POOL_NAME);
        this.client = client;
        this.transformServices = transformServices;
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.resolver = resolver;
        this.auditor = transformServices.getAuditor();
        this.numFailureRetries = Transform.NUM_FAILURE_RETRIES_SETTING.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(Transform.NUM_FAILURE_RETRIES_SETTING, this::setNumFailureRetries);
    }

    @Override
    public PersistentTasksCustomMetaData.Assignment getAssignment(TransformTaskParams params, ClusterState clusterState) {
        List<String> unavailableIndices = verifyIndicesPrimaryShardsAreActive(clusterState, resolver);
        if (unavailableIndices.size() != 0) {
            String reason = "Not starting transform ["
                + params.getId()
                + "], "
                + "because not all primary shards are active for the following indices ["
                + String.join(",", unavailableIndices)
                + "]";
            logger.debug(reason);
            return new PersistentTasksCustomMetaData.Assignment(null, reason);
        }

        // see gh#48019 disable assignment if any node is using 7.2 or 7.3
        if (clusterState.getNodes().getMinNodeVersion().before(Version.V_7_4_0)) {
            String reason = "Not starting transform ["
                + params.getId()
                + "], "
                + "because cluster contains nodes with version older than 7.4.0";
            logger.debug(reason);
            return new PersistentTasksCustomMetaData.Assignment(null, reason);
        }

        DiscoveryNode discoveryNode = selectLeastLoadedNode(
            clusterState,
            (node) -> node.getVersion().onOrAfter(Version.V_7_7_0)
                ? nodeCanRunThisTransform(node, params, null)
                : nodeCanRunThisTransformPre77(node, params, null)
        );

        if (discoveryNode == null) {
            Map<String, String> explainWhyAssignmentFailed = new TreeMap<>();
            for (DiscoveryNode node : clusterState.getNodes()) {
                if (node.getVersion().onOrAfter(Version.V_7_7_0)) {
                    nodeCanRunThisTransform(node, params, explainWhyAssignmentFailed);
                } else {
                    nodeCanRunThisTransformPre77(node, params, explainWhyAssignmentFailed);
                }
            }
            String reason = "Not starting transform ["
                + params.getId()
                + "], reasons ["
                + explainWhyAssignmentFailed.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining("|"))
                + "]";

            logger.debug(reason);
            return new PersistentTasksCustomMetaData.Assignment(null, reason);
        }

        return new PersistentTasksCustomMetaData.Assignment(discoveryNode.getId(), "");
    }

    public static boolean nodeCanRunThisTransformPre77(DiscoveryNode node, TransformTaskParams params, Map<String, String> explain) {
        if (node.isDataNode() == false) {
            if (explain != null) {
                explain.put(node.getId(), "not a data node");
            }
            return false;
        }

        // version of the transform run on a node that has at least the same version
        if (node.getVersion().onOrAfter(params.getVersion()) == false) {
            if (explain != null) {
                explain.put(
                    node.getId(),
                    "node has version: " + node.getVersion() + " but transform requires at least " + params.getVersion()
                );
            }
            return false;
        }

        return true;
    }

    public static boolean nodeCanRunThisTransform(DiscoveryNode node, TransformTaskParams params, Map<String, String> explain) {
        // version of the transform run on a node that has at least the same version
        if (node.getVersion().onOrAfter(params.getVersion()) == false) {
            if (explain != null) {
                explain.put(
                    node.getId(),
                    "node has version: " + node.getVersion() + " but transform requires at least " + params.getVersion()
                );
            }
            return false;
        }

        final Map<String, String> nodeAttributes = node.getAttributes();

        // transform enabled?
        if (Boolean.parseBoolean(nodeAttributes.get(Transform.TRANSFORM_ENABLED_NODE_ATTR)) == false) {
            if (explain != null) {
                explain.put(node.getId(), "not a transform node");
            }
            return false;
        }

        // does the transform require a remote and remote is enabled?
        if (params.requiresRemote() && node.isRemoteClusterClient() == false) {
            if (explain != null) {
                explain.put(node.getId(), "transform requires a remote connection but remote is disabled");
            }
            return false;
        }

        // we found no reason that the transform can not run on this node
        return true;
    }

    static List<String> verifyIndicesPrimaryShardsAreActive(ClusterState clusterState, IndexNameExpressionResolver resolver) {
        String[] indices = resolver.concreteIndexNames(
            clusterState,
            IndicesOptions.lenientExpandOpen(),
            TransformInternalIndexConstants.INDEX_NAME_PATTERN,
            TransformInternalIndexConstants.INDEX_NAME_PATTERN_DEPRECATED
        );
        List<String> unavailableIndices = new ArrayList<>(indices.length);
        for (String index : indices) {
            IndexRoutingTable routingTable = clusterState.getRoutingTable().index(index);
            if (routingTable == null || routingTable.allPrimaryShardsActive() == false) {
                unavailableIndices.add(index);
            }
        }
        return unavailableIndices;
    }

    @Override
    protected void nodeOperation(AllocatedPersistentTask task, @Nullable TransformTaskParams params, PersistentTaskState state) {
        final String transformId = params.getId();
        final TransformTask buildTask = (TransformTask) task;
        // NOTE: TransformPersistentTasksExecutor#createTask pulls in the stored task state from the ClusterState when the object
        // is created. TransformTask#ctor takes into account setting the task as failed if that is passed in with the
        // persisted state.
        // TransformPersistentTasksExecutor#startTask will fail as TransformTask#start, when force == false, will return
        // a failure indicating that a failed task cannot be started.
        //
        // We want the rest of the state to be populated in the task when it is loaded on the node so that users can force start it again
        // later if they want.

        final ClientTransformIndexerBuilder indexerBuilder = new ClientTransformIndexerBuilder().setAuditor(auditor)
            .setClient(client)
            .setTransformsCheckpointService(transformServices.getCheckpointService())
            .setTransformsConfigManager(transformServices.getConfigManager());

        final SetOnce<TransformState> stateHolder = new SetOnce<>();

        ActionListener<StartTransformAction.Response> startTaskListener = ActionListener.wrap(
            response -> logger.info("[{}] successfully completed and scheduled task in node operation", transformId),
            failure -> {
                auditor.error(
                    transformId,
                    "Failed to start transform. " + "Please stop and attempt to start again. Failure: " + failure.getMessage()
                );
                logger.error("Failed to start task [" + transformId + "] in node operation", failure);
            }
        );

        // <7> load next checkpoint
        ActionListener<TransformCheckpoint> getTransformNextCheckpointListener = ActionListener.wrap(nextCheckpoint -> {

            if (nextCheckpoint.isEmpty()) {
                // extra safety: reset position and progress if next checkpoint is empty
                // prevents a failure if for some reason the next checkpoint has been deleted
                indexerBuilder.setInitialPosition(null);
                indexerBuilder.setProgress(null);
            } else {
                logger.trace("[{}] Loaded next checkpoint [{}] found, starting the task", transformId, nextCheckpoint.getCheckpoint());
                indexerBuilder.setNextCheckpoint(nextCheckpoint);
            }

            final long lastCheckpoint = stateHolder.get().getCheckpoint();

            startTask(buildTask, indexerBuilder, lastCheckpoint, startTaskListener);
        }, error -> {
            // TODO: do not use the same error message as for loading the last checkpoint
            String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CHECKPOINT, transformId);
            logger.error(msg, error);
            markAsFailed(buildTask, msg);
        });

        // <6> load last checkpoint
        ActionListener<TransformCheckpoint> getTransformLastCheckpointListener = ActionListener.wrap(lastCheckpoint -> {
            indexerBuilder.setLastCheckpoint(lastCheckpoint);

            logger.trace("[{}] Loaded last checkpoint [{}], looking for next checkpoint", transformId, lastCheckpoint.getCheckpoint());
            transformServices.getConfigManager()
                .getTransformCheckpoint(transformId, lastCheckpoint.getCheckpoint() + 1, getTransformNextCheckpointListener);
        }, error -> {
            String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CHECKPOINT, transformId);
            logger.error(msg, error);
            markAsFailed(buildTask, msg);
        });

        // <5> Set the previous stats (if they exist), initialize the indexer, start the task (If it is STOPPED)
        // Since we don't create the task until `_start` is called, if we see that the task state is stopped, attempt to start
        // Schedule execution regardless
        ActionListener<Tuple<TransformStoredDoc, SeqNoPrimaryTermAndIndex>> transformStatsActionListener = ActionListener.wrap(
            stateAndStatsAndSeqNoPrimaryTermAndIndex -> {
                TransformStoredDoc stateAndStats = stateAndStatsAndSeqNoPrimaryTermAndIndex.v1();
                SeqNoPrimaryTermAndIndex seqNoPrimaryTermAndIndex = stateAndStatsAndSeqNoPrimaryTermAndIndex.v2();
                // Since we have not set the value for this yet, it SHOULD be null
                logger.trace("[{}] initializing state and stats: [{}]", transformId, stateAndStats.toString());
                TransformState transformState = stateAndStats.getTransformState();
                indexerBuilder.setInitialStats(stateAndStats.getTransformStats())
                    .setInitialPosition(stateAndStats.getTransformState().getPosition())
                    .setProgress(stateAndStats.getTransformState().getProgress())
                    .setIndexerState(currentIndexerState(transformState))
                    .setSeqNoPrimaryTermAndIndex(seqNoPrimaryTermAndIndex)
                    .setShouldStopAtCheckpoint(transformState.shouldStopAtNextCheckpoint());
                logger.debug(
                    "[{}] Loading existing state: [{}], position [{}]",
                    transformId,
                    stateAndStats.getTransformState(),
                    stateAndStats.getTransformState().getPosition()
                );

                stateHolder.set(transformState);
                final long lastCheckpoint = stateHolder.get().getCheckpoint();

                if (lastCheckpoint == 0) {
                    logger.trace("[{}] No last checkpoint found, looking for next checkpoint", transformId);
                    transformServices.getConfigManager()
                        .getTransformCheckpoint(transformId, lastCheckpoint + 1, getTransformNextCheckpointListener);
                } else {
                    logger.trace("[{}] Restore last checkpoint: [{}]", transformId, lastCheckpoint);
                    transformServices.getConfigManager()
                        .getTransformCheckpoint(transformId, lastCheckpoint, getTransformLastCheckpointListener);
                }
            },
            error -> {
                if (error instanceof ResourceNotFoundException == false) {
                    String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_STATE, transformId);
                    logger.error(msg, error);
                    markAsFailed(buildTask, msg);
                } else {
                    logger.trace("[{}] No stats found (new transform), starting the task", transformId);
                    startTask(buildTask, indexerBuilder, null, startTaskListener);
                }
            }
        );

        // <4> set fieldmappings for the indexer, get the previous stats (if they exist)
        ActionListener<Map<String, String>> getFieldMappingsListener = ActionListener.wrap(fieldMappings -> {
            indexerBuilder.setFieldMappings(fieldMappings);
            transformServices.getConfigManager().getTransformStoredDoc(transformId, transformStatsActionListener);
        }, error -> {
            String msg = TransformMessages.getMessage(
                TransformMessages.UNABLE_TO_GATHER_FIELD_MAPPINGS,
                indexerBuilder.getTransformConfig().getDestination().getIndex()
            );
            logger.error(msg, error);
            markAsFailed(buildTask, msg);
        });

        // <3> Validate the transform, assigning it to the indexer, and get the field mappings
        ActionListener<TransformConfig> getTransformConfigListener = ActionListener.wrap(config -> {
            if (config.isValid()) {
                indexerBuilder.setTransformConfig(config);
                SchemaUtil.getDestinationFieldMappings(client, config.getDestination().getIndex(), getFieldMappingsListener);
            } else {
                markAsFailed(buildTask, TransformMessages.getMessage(TransformMessages.TRANSFORM_CONFIGURATION_INVALID, transformId));
            }
        }, error -> {
            String msg = TransformMessages.getMessage(TransformMessages.FAILED_TO_LOAD_TRANSFORM_CONFIGURATION, transformId);
            logger.error(msg, error);
            markAsFailed(buildTask, msg);
        });

        // <2> Get the transform config
        ActionListener<Void> templateCheckListener = ActionListener.wrap(
            aVoid -> transformServices.getConfigManager().getTransformConfiguration(transformId, getTransformConfigListener),
            error -> {
                Throwable cause = ExceptionsHelper.unwrapCause(error);
                String msg = "Failed to create internal index mappings";
                logger.error(msg, cause);
                markAsFailed(buildTask, msg + "[" + cause + "]");
            }
        );

        // <1> Check the index templates are installed
        TransformInternalIndex.installLatestIndexTemplatesIfRequired(clusterService, client, templateCheckListener);
    }

    private static IndexerState currentIndexerState(TransformState previousState) {
        if (previousState == null) {
            return IndexerState.STOPPED;
        }
        switch (previousState.getIndexerState()) {
            // If it is STARTED or INDEXING we want to make sure we revert to started
            // Otherwise, the internal indexer will never get scheduled and execute
            case STARTED:
            case INDEXING:
                return IndexerState.STARTED;
            // If we are STOPPED, STOPPING, or ABORTING and just started executing on this node,
            // then it is safe to say we should be STOPPED
            case STOPPED:
            case STOPPING:
            case ABORTING:
            default:
                return IndexerState.STOPPED;
        }
    }

    private void markAsFailed(TransformTask task, String reason) {
        CountDownLatch latch = new CountDownLatch(1);

        task.fail(
            reason,
            new LatchedActionListener<>(
                ActionListener.wrap(
                    nil -> {},
                    failure -> logger.error("Failed to set task [" + task.getTransformId() + "] to failed", failure)
                ),
                latch
            )
        );
        try {
            latch.await(MARK_AS_FAILED_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.error("Timeout waiting for task [" + task.getTransformId() + "] to be marked as failed in cluster state", e);
        }
    }

    private void startTask(
        TransformTask buildTask,
        ClientTransformIndexerBuilder indexerBuilder,
        Long previousCheckpoint,
        ActionListener<StartTransformAction.Response> listener
    ) {
        buildTask.initializeIndexer(indexerBuilder);
        // TransformTask#start will fail if the task state is FAILED
        buildTask.setNumFailureRetries(numFailureRetries).start(previousCheckpoint, listener);
    }

    private void setNumFailureRetries(int numFailureRetries) {
        this.numFailureRetries = numFailureRetries;
    }

    @Override
    protected AllocatedPersistentTask createTask(
        long id,
        String type,
        String action,
        TaskId parentTaskId,
        PersistentTasksCustomMetaData.PersistentTask<TransformTaskParams> persistentTask,
        Map<String, String> headers
    ) {
        return new TransformTask(
            id,
            type,
            action,
            parentTaskId,
            persistentTask.getParams(),
            (TransformState) persistentTask.getState(),
            transformServices.getSchedulerEngine(),
            auditor,
            threadPool,
            headers
        );
    }
}
