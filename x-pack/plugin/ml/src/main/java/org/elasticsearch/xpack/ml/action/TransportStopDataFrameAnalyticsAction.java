/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.TaskOperationFailure;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.tasks.TransportTasksAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.StopDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsState;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsTaskState;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.dataframe.DataFrameAnalyticsTask;
import org.elasticsearch.xpack.ml.dataframe.persistence.DataFrameAnalyticsConfigProvider;
import org.elasticsearch.xpack.ml.notifications.DataFrameAnalyticsAuditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stops the persistent task for running data frame analytics.
 *
 * TODO Add to the upgrade mode action
 */
public class TransportStopDataFrameAnalyticsAction
    extends TransportTasksAction<DataFrameAnalyticsTask, StopDataFrameAnalyticsAction.Request,
        StopDataFrameAnalyticsAction.Response, StopDataFrameAnalyticsAction.Response> {

    private static final Logger logger = LogManager.getLogger(TransportStopDataFrameAnalyticsAction.class);

    private final ThreadPool threadPool;
    private final PersistentTasksService persistentTasksService;
    private final DataFrameAnalyticsConfigProvider configProvider;
    private final DataFrameAnalyticsAuditor auditor;

    @Inject
    public TransportStopDataFrameAnalyticsAction(TransportService transportService, ActionFilters actionFilters,
                                                 ClusterService clusterService, ThreadPool threadPool,
                                                 PersistentTasksService persistentTasksService,
                                                 DataFrameAnalyticsConfigProvider configProvider,
                                                 DataFrameAnalyticsAuditor auditor) {
        super(StopDataFrameAnalyticsAction.NAME, clusterService, transportService, actionFilters, StopDataFrameAnalyticsAction.Request::new,
            StopDataFrameAnalyticsAction.Response::new, StopDataFrameAnalyticsAction.Response::new, ThreadPool.Names.SAME);
        this.threadPool = threadPool;
        this.persistentTasksService = persistentTasksService;
        this.configProvider = configProvider;
        this.auditor = Objects.requireNonNull(auditor);
    }

    @Override
    protected void doExecute(Task task, StopDataFrameAnalyticsAction.Request request,
                             ActionListener<StopDataFrameAnalyticsAction.Response> listener) {
        ClusterState state = clusterService.state();
        DiscoveryNodes nodes = state.nodes();
        if (nodes.isLocalNodeElectedMaster() == false) {
            redirectToMasterNode(nodes.getMasterNode(), request, listener);
            return;
        }

        logger.debug("Received request to stop data frame analytics [{}]", request.getId());

        ActionListener<Set<String>> expandedIdsListener = ActionListener.wrap(
            expandedIds -> {
                logger.debug("Resolved data frame analytics to stop: {}", expandedIds);

                PersistentTasksCustomMetaData tasks = state.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
                AnalyticsByTaskState analyticsByTaskState = AnalyticsByTaskState.build(expandedIds, tasks);

                if (analyticsByTaskState.isEmpty()) {
                    listener.onResponse(new StopDataFrameAnalyticsAction.Response(true));
                    return;
                }

                if (request.isForce()) {
                    forceStop(request, listener, tasks, analyticsByTaskState.getNonStopped());
                } else {
                    normalStop(task, request, listener, tasks, analyticsByTaskState);
                }
            },
            listener::onFailure
        );

        expandIds(state, request, expandedIdsListener);
    }

    private void expandIds(ClusterState clusterState, StopDataFrameAnalyticsAction.Request request,
                           ActionListener<Set<String>> expandedIdsListener) {
        ActionListener<List<DataFrameAnalyticsConfig>> configsListener = ActionListener.wrap(
            configs -> {
                Set<String> matchingIds = configs.stream().map(DataFrameAnalyticsConfig::getId).collect(Collectors.toSet());
                PersistentTasksCustomMetaData tasksMetaData = clusterState.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
                Set<String> startedIds = tasksMetaData == null ? Collections.emptySet() : tasksMetaData.tasks().stream()
                    .filter(t -> t.getId().startsWith(MlTasks.DATA_FRAME_ANALYTICS_TASK_ID_PREFIX))
                    .map(t -> t.getId().replaceFirst(MlTasks.DATA_FRAME_ANALYTICS_TASK_ID_PREFIX, ""))
                    .collect(Collectors.toSet());
                startedIds.retainAll(matchingIds);
                expandedIdsListener.onResponse(startedIds);
            },
            expandedIdsListener::onFailure
        );

        configProvider.getMultiple(request.getId(), request.allowNoMatch(), configsListener);
    }

    private void normalStop(Task task, StopDataFrameAnalyticsAction.Request request,
                            ActionListener<StopDataFrameAnalyticsAction.Response> listener,
                            PersistentTasksCustomMetaData tasks, AnalyticsByTaskState analyticsByTaskState) {
        if (analyticsByTaskState.failed.isEmpty() == false) {
            ElasticsearchStatusException e = analyticsByTaskState.failed.size() == 1 ? ExceptionsHelper.conflictStatusException(
                "cannot close data frame analytics [{}] because it failed, use force stop instead",
                analyticsByTaskState.failed.iterator().next()) :
                ExceptionsHelper.conflictStatusException("one or more data frame analytics are in failed state, use force stop instead");
            listener.onFailure(e);
            return;
        }

        request.setExpandedIds(new HashSet<>(analyticsByTaskState.started));
        request.setNodes(findAllocatedNodesAndRemoveUnassignedTasks(analyticsByTaskState.started, tasks));

        // Wait for started and stopping analytics
        Set<String> allAnalyticsToWaitFor = Stream.concat(
                analyticsByTaskState.started.stream().map(MlTasks::dataFrameAnalyticsTaskId),
                analyticsByTaskState.stopping.stream().map(MlTasks::dataFrameAnalyticsTaskId)
            ).collect(Collectors.toSet());

        ActionListener<StopDataFrameAnalyticsAction.Response> finalListener = ActionListener.wrap(
            r -> waitForTaskRemoved(allAnalyticsToWaitFor, request, r, listener),
            e -> {
                if (ExceptionsHelper.unwrapCause(e) instanceof FailedNodeException) {
                    // A node has dropped out of the cluster since we started executing the requests.
                    // Since stopping an already stopped analytics is not an error we can try again.
                    // The analytics that were running on the node that dropped out of the cluster
                    // will just have their persistent tasks cancelled. Analytics that were stopped
                    // by the previous attempt will be noops in the subsequent attempt.
                    doExecute(task, request, listener);
                } else {
                    listener.onFailure(e);
                }
            }
        );

        super.doExecute(task, request, finalListener);
    }

    private void forceStop(StopDataFrameAnalyticsAction.Request request, ActionListener<StopDataFrameAnalyticsAction.Response> listener,
                           PersistentTasksCustomMetaData tasks, List<String> nonStoppedAnalytics) {

        final AtomicInteger counter = new AtomicInteger();
        final AtomicArray<Exception> failures = new AtomicArray<>(nonStoppedAnalytics.size());

        for (String analyticsId : nonStoppedAnalytics) {
            PersistentTasksCustomMetaData.PersistentTask<?> analyticsTask = MlTasks.getDataFrameAnalyticsTask(analyticsId, tasks);
            if (analyticsTask != null) {
                persistentTasksService.sendRemoveRequest(analyticsTask.getId(), ActionListener.wrap(
                    removedTask -> {
                        if (counter.incrementAndGet() == nonStoppedAnalytics.size()) {
                            sendResponseOrFailure(request.getId(), listener, failures);
                        }
                    },
                    e -> {
                        final int slot = counter.incrementAndGet();
                        // We validated that the analytics ids supplied in the request existed when we started processing the action.
                        // If the related tasks don't exist at this point then they must have been stopped by a simultaneous stop request.
                        // This is not an error.
                        if (ExceptionsHelper.unwrapCause(e) instanceof ResourceNotFoundException == false) {
                            failures.set(slot - 1, e);
                        }
                        if (slot == nonStoppedAnalytics.size()) {
                            sendResponseOrFailure(request.getId(), listener, failures);
                        }
                    }
                ));
            } else {
                // This should not happen, because nonStoppedAnalytics
                // were derived from the same tasks that were passed to this method
                String msg = "Requested data frame analytics [" + analyticsId + "] be force-stopped, but no task could be found.";
                assert analyticsTask != null : msg;
                logger.error(msg);
                final int slot = counter.incrementAndGet();
                failures.set(slot - 1, new RuntimeException(msg));
                if (slot == nonStoppedAnalytics.size()) {
                    sendResponseOrFailure(request.getId(), listener, failures);
                }
            }
        }
    }

    private void sendResponseOrFailure(String analyticsId, ActionListener<StopDataFrameAnalyticsAction.Response> listener,
                                       AtomicArray<Exception> failures) {
        List<Exception> caughtExceptions = failures.asList();
        if (caughtExceptions.size() == 0) {
            listener.onResponse(new StopDataFrameAnalyticsAction.Response(true));
            return;
        }

        String msg = "Failed to stop data frame analytics [" + analyticsId + "] with [" + caughtExceptions.size()
            + "] failures, rethrowing last, all Exceptions: ["
            + caughtExceptions.stream().map(Exception::getMessage).collect(Collectors.joining(", "))
            + "]";

        ElasticsearchException e = new ElasticsearchException(msg, caughtExceptions.get(0));
        listener.onFailure(e);
    }

    private String[] findAllocatedNodesAndRemoveUnassignedTasks(List<String> analyticsIds, PersistentTasksCustomMetaData tasks) {
        List<String> nodes = new ArrayList<>();
        for (String analyticsId : analyticsIds) {
            PersistentTasksCustomMetaData.PersistentTask<?> task = MlTasks.getDataFrameAnalyticsTask(analyticsId, tasks);
            if (task == null) {
                // This should not be possible; we filtered started analytics thus the task should exist
                String msg = "Requested data frame analytics [" + analyticsId + "] be stopped but the task could not be found";
                assert task != null : msg;
            } else if (task.isAssigned()) {
                nodes.add(task.getExecutorNode());
            } else {
                // This means the task has not been assigned to a node yet so
                // we can stop it by removing its persistent task.
                // The listener is a no-op as we're already going to wait for the task to be removed.
                persistentTasksService.sendRemoveRequest(task.getId(), ActionListener.wrap(r -> {}, e -> {}));
            }
        }
        return nodes.toArray(new String[0]);
    }

    private void redirectToMasterNode(DiscoveryNode masterNode, StopDataFrameAnalyticsAction.Request request,
                                      ActionListener<StopDataFrameAnalyticsAction.Response> listener) {
        if (masterNode == null) {
            listener.onFailure(new MasterNotDiscoveredException("no known master node"));
        } else {
            transportService.sendRequest(masterNode, actionName, request,
                new ActionListenerResponseHandler<>(listener, StopDataFrameAnalyticsAction.Response::new));
        }
    }

    @Override
    protected StopDataFrameAnalyticsAction.Response newResponse(StopDataFrameAnalyticsAction.Request request,
                                                                List<StopDataFrameAnalyticsAction.Response> tasks,
                                                                List<TaskOperationFailure> taskOperationFailures,
                                                                List<FailedNodeException> failedNodeExceptions) {
        if (request.getExpandedIds().size() != tasks.size()) {
            if (taskOperationFailures.isEmpty() == false) {
                throw org.elasticsearch.ExceptionsHelper.convertToElastic(taskOperationFailures.get(0).getCause());
            } else if (failedNodeExceptions.isEmpty() == false) {
                throw org.elasticsearch.ExceptionsHelper.convertToElastic(failedNodeExceptions.get(0));
            } else {
                // This can happen when the actual task in the node no longer exists,
                // which means the data frame analytic(s) have already been closed.
                return new StopDataFrameAnalyticsAction.Response(true);
            }
        }
        return new StopDataFrameAnalyticsAction.Response(tasks.stream().allMatch(StopDataFrameAnalyticsAction.Response::isStopped));
    }

    @Override
    protected void taskOperation(StopDataFrameAnalyticsAction.Request request,
                                 DataFrameAnalyticsTask task,
                                 ActionListener<StopDataFrameAnalyticsAction.Response> listener) {
        DataFrameAnalyticsTaskState stoppingState =
            new DataFrameAnalyticsTaskState(DataFrameAnalyticsState.STOPPING, task.getAllocationId(), null);
        task.updatePersistentTaskState(stoppingState, ActionListener.wrap(pTask -> {
                threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME).execute(new AbstractRunnable() {
                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }

                    @Override
                    protected void doRun() {
                        logger.info("[{}] Stopping task with force [{}]", task.getParams().getId(), request.isForce());
                        task.stop("stop_data_frame_analytics (api)", request.getTimeout());
                        listener.onResponse(new StopDataFrameAnalyticsAction.Response(true));
                    }
                });
            },
            e -> {
                if (ExceptionsHelper.unwrapCause(e) instanceof ResourceNotFoundException) {
                    // the task has disappeared so must have stopped
                    listener.onResponse(new StopDataFrameAnalyticsAction.Response(true));
                } else {
                    listener.onFailure(e);
                }
            }));
    }

    void waitForTaskRemoved(Set<String> taskIds, StopDataFrameAnalyticsAction.Request request,
                            StopDataFrameAnalyticsAction.Response response,
                            ActionListener<StopDataFrameAnalyticsAction.Response> listener) {
        persistentTasksService.waitForPersistentTasksCondition(persistentTasks ->
                persistentTasks.findTasks(MlTasks.DATA_FRAME_ANALYTICS_TASK_NAME, t -> taskIds.contains(t.getId())).isEmpty(),
            request.getTimeout(), ActionListener.wrap(
                booleanResponse -> {
                    auditor.info(request.getId(), Messages.DATA_FRAME_ANALYTICS_AUDIT_STOPPED);
                    listener.onResponse(response);
                },
                listener::onFailure
            ));
    }

    // Visible for testing
    static class AnalyticsByTaskState {

        final List<String> started;
        final List<String> stopping;
        final List<String> failed;

        private AnalyticsByTaskState(List<String> started, List<String> stopping, List<String> failed) {
            this.started = Collections.unmodifiableList(started);
            this.stopping = Collections.unmodifiableList(stopping);
            this.failed = Collections.unmodifiableList(failed);
        }

        boolean isEmpty() {
            return started.isEmpty() && stopping.isEmpty() && failed.isEmpty();
        }

        List<String> getNonStopped() {
            List<String> nonStopped = new ArrayList<>();
            nonStopped.addAll(started);
            nonStopped.addAll(stopping);
            nonStopped.addAll(failed);
            return nonStopped;
        }

        static AnalyticsByTaskState build(Set<String> analyticsIds, PersistentTasksCustomMetaData tasks) {
            List<String> started = new ArrayList<>();
            List<String> stopping = new ArrayList<>();
            List<String> failed = new ArrayList<>();

            for (String analyticsId : analyticsIds) {
                DataFrameAnalyticsState taskState = MlTasks.getDataFrameAnalyticsState(analyticsId, tasks);
                switch (taskState) {
                    case STARTING:
                    case STARTED:
                    case REINDEXING:
                    case ANALYZING:
                        started.add(analyticsId);
                        break;
                    case STOPPING:
                        stopping.add(analyticsId);
                        break;
                    case STOPPED:
                        break;
                    case FAILED:
                        failed.add(analyticsId);
                        break;
                    default:
                        assert false : "unknown task state " + taskState;
                }
            }
            return new AnalyticsByTaskState(started, stopping, failed);
        }
    }
}
