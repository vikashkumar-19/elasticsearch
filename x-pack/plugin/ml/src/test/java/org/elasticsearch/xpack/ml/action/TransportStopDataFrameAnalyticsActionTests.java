/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.Version;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ml.MlTasks;
import org.elasticsearch.xpack.core.ml.action.StartDataFrameAnalyticsAction;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsState;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsTaskState;
import org.elasticsearch.xpack.ml.action.TransportStopDataFrameAnalyticsAction.AnalyticsByTaskState;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

public class TransportStopDataFrameAnalyticsActionTests extends ESTestCase {

    public void testAnalyticsByTaskState_GivenEmpty() {
        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();

        AnalyticsByTaskState analyticsByTaskState = AnalyticsByTaskState.build(Collections.emptySet(), tasksBuilder.build());

        assertThat(analyticsByTaskState.isEmpty(), is(true));
    }

    public void testAnalyticsByTaskState_GivenAllStates() {
        PersistentTasksCustomMetaData.Builder tasksBuilder =  PersistentTasksCustomMetaData.builder();
        addAnalyticsTask(tasksBuilder, "starting", "foo-node", null);
        addAnalyticsTask(tasksBuilder, "started", "foo-node", DataFrameAnalyticsState.STARTED);
        addAnalyticsTask(tasksBuilder, "reindexing", "foo-node", DataFrameAnalyticsState.REINDEXING);
        addAnalyticsTask(tasksBuilder, "analyzing", "foo-node", DataFrameAnalyticsState.ANALYZING);
        addAnalyticsTask(tasksBuilder, "stopping", "foo-node", DataFrameAnalyticsState.STOPPING);
        addAnalyticsTask(tasksBuilder, "stopped", "foo-node", DataFrameAnalyticsState.STOPPED);
        addAnalyticsTask(tasksBuilder, "failed", "foo-node", DataFrameAnalyticsState.FAILED);

        Set<String> ids = new HashSet<>(Arrays.asList("starting", "started", "reindexing", "analyzing", "stopping", "stopped", "failed"));

        AnalyticsByTaskState analyticsByTaskState = AnalyticsByTaskState.build(ids, tasksBuilder.build());

        assertThat(analyticsByTaskState.isEmpty(), is(false));
        assertThat(analyticsByTaskState.started, containsInAnyOrder("starting", "started", "reindexing", "analyzing"));
        assertThat(analyticsByTaskState.stopping, containsInAnyOrder("stopping"));
        assertThat(analyticsByTaskState.failed, containsInAnyOrder("failed"));
        assertThat(analyticsByTaskState.getNonStopped(), containsInAnyOrder(
            "starting", "started", "reindexing", "analyzing", "stopping", "failed"));;
    }

    private static void addAnalyticsTask(PersistentTasksCustomMetaData.Builder builder, String analyticsId, String nodeId,
                                         DataFrameAnalyticsState state) {
        addAnalyticsTask(builder, analyticsId, nodeId, state, false);
    }

    private static void addAnalyticsTask(PersistentTasksCustomMetaData.Builder builder, String analyticsId, String nodeId,
                                         DataFrameAnalyticsState state, boolean allowLazyStart) {
        builder.addTask(MlTasks.dataFrameAnalyticsTaskId(analyticsId), MlTasks.DATA_FRAME_ANALYTICS_TASK_NAME,
            new StartDataFrameAnalyticsAction.TaskParams(analyticsId, Version.CURRENT, Collections.emptyList(), allowLazyStart),
            new PersistentTasksCustomMetaData.Assignment(nodeId, "test assignment"));

        if (state != null) {
            builder.updateTaskState(MlTasks.dataFrameAnalyticsTaskId(analyticsId),
                new DataFrameAnalyticsTaskState(state, builder.getLastAllocationId(), null));
        }
    }
}
