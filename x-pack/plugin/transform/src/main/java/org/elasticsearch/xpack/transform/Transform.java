/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.transform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.NamedXContentRegistry.Entry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.scheduler.SchedulerEngine;
import org.elasticsearch.xpack.core.transform.TransformNamedXContentProvider;
import org.elasticsearch.xpack.core.transform.action.DeleteTransformAction;
import org.elasticsearch.xpack.core.transform.action.GetTransformAction;
import org.elasticsearch.xpack.core.transform.action.GetTransformStatsAction;
import org.elasticsearch.xpack.core.transform.action.PreviewTransformAction;
import org.elasticsearch.xpack.core.transform.action.PutTransformAction;
import org.elasticsearch.xpack.core.transform.action.StartTransformAction;
import org.elasticsearch.xpack.core.transform.action.StopTransformAction;
import org.elasticsearch.xpack.core.transform.action.UpdateTransformAction;
import org.elasticsearch.xpack.core.transform.action.compat.DeleteTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.GetTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.GetTransformStatsActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.PreviewTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.PutTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.StartTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.StopTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.action.compat.UpdateTransformActionDeprecated;
import org.elasticsearch.xpack.core.transform.transforms.persistence.TransformInternalIndexConstants;
import org.elasticsearch.xpack.transform.action.TransportDeleteTransformAction;
import org.elasticsearch.xpack.transform.action.TransportGetTransformAction;
import org.elasticsearch.xpack.transform.action.TransportGetTransformStatsAction;
import org.elasticsearch.xpack.transform.action.TransportPreviewTransformAction;
import org.elasticsearch.xpack.transform.action.TransportPutTransformAction;
import org.elasticsearch.xpack.transform.action.TransportStartTransformAction;
import org.elasticsearch.xpack.transform.action.TransportStopTransformAction;
import org.elasticsearch.xpack.transform.action.TransportUpdateTransformAction;
import org.elasticsearch.xpack.transform.action.compat.TransportDeleteTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportGetTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportGetTransformStatsActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportPreviewTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportPutTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportStartTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportStopTransformActionDeprecated;
import org.elasticsearch.xpack.transform.action.compat.TransportUpdateTransformActionDeprecated;
import org.elasticsearch.xpack.transform.checkpoint.TransformCheckpointService;
import org.elasticsearch.xpack.transform.notifications.TransformAuditor;
import org.elasticsearch.xpack.transform.persistence.IndexBasedTransformConfigManager;
import org.elasticsearch.xpack.transform.persistence.TransformConfigManager;
import org.elasticsearch.xpack.transform.persistence.TransformInternalIndex;
import org.elasticsearch.xpack.transform.rest.action.RestCatTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestDeleteTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestGetTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestGetTransformStatsAction;
import org.elasticsearch.xpack.transform.rest.action.RestPreviewTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestPutTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestStartTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestStopTransformAction;
import org.elasticsearch.xpack.transform.rest.action.RestUpdateTransformAction;
import org.elasticsearch.xpack.transform.rest.action.compat.RestDeleteTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestGetTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestGetTransformStatsActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestPreviewTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestPutTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestStartTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestStopTransformActionDeprecated;
import org.elasticsearch.xpack.transform.rest.action.compat.RestUpdateTransformActionDeprecated;
import org.elasticsearch.xpack.transform.transforms.TransformPersistentTasksExecutor;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;

public class Transform extends Plugin implements SystemIndexPlugin, PersistentTaskPlugin {

    public static final String NAME = "transform";
    public static final String TASK_THREAD_POOL_NAME = "transform_indexing";

    private static final Logger logger = LogManager.getLogger(Transform.class);

    private final boolean enabled;
    private final Settings settings;
    private final boolean transportClientMode;
    private final SetOnce<TransformServices> transformServices = new SetOnce<>();

    public static final int DEFAULT_FAILURE_RETRIES = 10;

    // How many times the transform task can retry on an non-critical failure
    public static final Setting<Integer> NUM_FAILURE_RETRIES_SETTING = Setting.intSetting(
        "xpack.transform.num_transform_failure_retries",
        DEFAULT_FAILURE_RETRIES,
        0,
        100,
        Setting.Property.NodeScope,
        Setting.Property.Dynamic
    );

    /**
     * Node attributes for transform, automatically created and retrievable via cluster state.
     * These attributes should never be set directly, use the node setting counter parts instead.
     */
    public static final String TRANSFORM_ENABLED_NODE_ATTR = "transform.node";

    /**
     * Setting whether transform (the coordinator task) can run on this node and REST API's are available,
     * respects xpack.transform.enabled (for the whole plugin) as fallback
     */
    public static final Setting<Boolean> TRANSFORM_ENABLED_NODE = Setting.boolSetting(
        "node.transform",
        settings -> Boolean.toString(XPackSettings.TRANSFORM_ENABLED.get(settings) && DiscoveryNode.isDataNode(settings)),
        Property.NodeScope
    );

    public static final DiscoveryNodeRole TRANSFORM_ROLE = new DiscoveryNodeRole("transform", "t") {

        @Override
        protected Setting<Boolean> roleSetting() {
            return TRANSFORM_ENABLED_NODE;
        }

    };

    public Transform(Settings settings) {
        this.settings = settings;
        this.enabled = XPackSettings.TRANSFORM_ENABLED.get(settings);
        this.transportClientMode = XPackPlugin.transportClientMode(settings);
    }

    @Override
    public Collection<Module> createGuiceModules() {
        List<Module> modules = new ArrayList<>();

        if (transportClientMode) {
            return modules;
        }

        modules.add(b -> XPackPlugin.bindFeatureSet(b, TransformFeatureSet.class));
        return modules;
    }

    protected XPackLicenseState getLicenseState() {
        return XPackPlugin.getSharedLicenseState();
    }

    @Override
    public List<RestHandler> getRestHandlers(
        final Settings settings,
        final RestController restController,
        final ClusterSettings clusterSettings,
        final IndexScopedSettings indexScopedSettings,
        final SettingsFilter settingsFilter,
        final IndexNameExpressionResolver indexNameExpressionResolver,
        final Supplier<DiscoveryNodes> nodesInCluster
    ) {

        if (!enabled) {
            return emptyList();
        }

        return Arrays.asList(
            new RestPutTransformAction(),
            new RestStartTransformAction(),
            new RestStopTransformAction(),
            new RestDeleteTransformAction(),
            new RestGetTransformAction(),
            new RestGetTransformStatsAction(),
            new RestPreviewTransformAction(),
            new RestUpdateTransformAction(),
            new RestCatTransformAction(),

            // deprecated endpoints, to be removed for 8.0.0
            new RestPutTransformActionDeprecated(),
            new RestStartTransformActionDeprecated(),
            new RestStopTransformActionDeprecated(),
            new RestDeleteTransformActionDeprecated(),
            new RestGetTransformActionDeprecated(),
            new RestGetTransformStatsActionDeprecated(),
            new RestPreviewTransformActionDeprecated(),
            new RestUpdateTransformActionDeprecated()
        );
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (!enabled) {
            return emptyList();
        }

        return Arrays.asList(
            new ActionHandler<>(PutTransformAction.INSTANCE, TransportPutTransformAction.class),
            new ActionHandler<>(StartTransformAction.INSTANCE, TransportStartTransformAction.class),
            new ActionHandler<>(StopTransformAction.INSTANCE, TransportStopTransformAction.class),
            new ActionHandler<>(DeleteTransformAction.INSTANCE, TransportDeleteTransformAction.class),
            new ActionHandler<>(GetTransformAction.INSTANCE, TransportGetTransformAction.class),
            new ActionHandler<>(GetTransformStatsAction.INSTANCE, TransportGetTransformStatsAction.class),
            new ActionHandler<>(PreviewTransformAction.INSTANCE, TransportPreviewTransformAction.class),
            new ActionHandler<>(UpdateTransformAction.INSTANCE, TransportUpdateTransformAction.class),

            // deprecated actions, to be removed for 8.0.0
            new ActionHandler<>(PutTransformActionDeprecated.INSTANCE, TransportPutTransformActionDeprecated.class),
            new ActionHandler<>(StartTransformActionDeprecated.INSTANCE, TransportStartTransformActionDeprecated.class),
            new ActionHandler<>(StopTransformActionDeprecated.INSTANCE, TransportStopTransformActionDeprecated.class),
            new ActionHandler<>(DeleteTransformActionDeprecated.INSTANCE, TransportDeleteTransformActionDeprecated.class),
            new ActionHandler<>(GetTransformActionDeprecated.INSTANCE, TransportGetTransformActionDeprecated.class),
            new ActionHandler<>(GetTransformStatsActionDeprecated.INSTANCE, TransportGetTransformStatsActionDeprecated.class),
            new ActionHandler<>(PreviewTransformActionDeprecated.INSTANCE, TransportPreviewTransformActionDeprecated.class),
            new ActionHandler<>(UpdateTransformActionDeprecated.INSTANCE, TransportUpdateTransformActionDeprecated.class)
        );
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        if (false == enabled || transportClientMode) {
            return emptyList();
        }

        FixedExecutorBuilder indexing = new FixedExecutorBuilder(
            settings,
            TASK_THREAD_POOL_NAME,
            4,
            4,
            "transform.task_thread_pool"
        );

        return Collections.singletonList(indexing);
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver expressionResolver
    ) {
        if (enabled == false || transportClientMode) {
            return emptyList();
        }

        TransformConfigManager configManager = new IndexBasedTransformConfigManager(client, xContentRegistry);
        TransformAuditor auditor = new TransformAuditor(client, clusterService.getNodeName());
        TransformCheckpointService checkpointService = new TransformCheckpointService(
            client,
            settings,
            clusterService,
            configManager,
            auditor
        );
        SchedulerEngine scheduler = new SchedulerEngine(settings, Clock.systemUTC());

        transformServices.set(new TransformServices(configManager, checkpointService, auditor, scheduler));

        return Arrays.asList(transformServices.get(), new TransformClusterStateListener(clusterService, client));
    }

    @Override
    public UnaryOperator<Map<String, IndexTemplateMetaData>> getIndexTemplateMetaDataUpgrader() {
        return templates -> {
            try {
                templates.put(
                    TransformInternalIndexConstants.LATEST_INDEX_VERSIONED_NAME,
                    TransformInternalIndex.getIndexTemplateMetaData()
                );
            } catch (IOException e) {
                logger.error("Error creating transform index template", e);
            }
            try {
                // Template upgraders are only ever called on the master nodes, so we can use the current node version as the compatibility
                // version here because we can be sure that this node, if elected master, will be compatible with itself.
                templates.put(TransformInternalIndexConstants.AUDIT_INDEX,
                    TransformInternalIndex.getAuditIndexTemplateMetaData(Version.CURRENT));
            } catch (IOException e) {
                logger.warn("Error creating transform audit index", e);
            }
            return templates;
        };
    }

    @Override
    public List<PersistentTasksExecutor<?>> getPersistentTasksExecutor(
        ClusterService clusterService,
        ThreadPool threadPool,
        Client client,
        SettingsModule settingsModule,
        IndexNameExpressionResolver expressionResolver
    ) {
        if (enabled == false || transportClientMode) {
            return emptyList();
        }

        // the transform services should have been created
        assert transformServices.get() != null;

        return Collections.singletonList(
            new TransformPersistentTasksExecutor(
                client,
                transformServices.get(),
                threadPool,
                clusterService,
                settingsModule.getSettings(),
                expressionResolver
            )
        );
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(Arrays.asList(TRANSFORM_ENABLED_NODE, NUM_FAILURE_RETRIES_SETTING));
    }

    @Override
    public Settings additionalSettings() {
        String transformEnabledNodeAttribute = "node.attr." + TRANSFORM_ENABLED_NODE_ATTR;

        if (settings.get(transformEnabledNodeAttribute) != null) {
            throw new IllegalArgumentException(
                "Directly setting transform node attributes is not permitted, please use the documented node settings instead"
            );
        }

        if (enabled == false) {
            return Settings.EMPTY;
        }

        Settings.Builder additionalSettings = Settings.builder();

        additionalSettings.put(transformEnabledNodeAttribute, TRANSFORM_ENABLED_NODE.get(settings));

        return additionalSettings.build();
    }

    @Override
    public Set<DiscoveryNodeRole> getRoles() {
        return Collections.singleton(TRANSFORM_ROLE);
    }

    @Override
    public void close() {
        if (transformServices.get() != null) {
            transformServices.get().getSchedulerEngine().stop();
        }
    }

    @Override
    public List<Entry> getNamedXContent() {
        return new TransformNamedXContentProvider().getNamedXContentParsers();
    }

    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors() {
        return Collections.singletonList(
            new SystemIndexDescriptor(TransformInternalIndexConstants.INDEX_NAME_PATTERN, "Contains Transform configuration data")
        );
    }
}
