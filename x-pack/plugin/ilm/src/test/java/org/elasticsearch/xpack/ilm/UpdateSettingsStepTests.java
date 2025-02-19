/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ilm;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.core.ilm.AsyncActionStep;
import org.elasticsearch.xpack.core.ilm.Step.StepKey;
import org.elasticsearch.xpack.core.ilm.UpdateSettingsStep;
import org.junit.After;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.xpack.ilm.UpdateSettingsStepTests.SettingsTestingService.INVALID_VALUE;
import static org.hamcrest.Matchers.is;

public class UpdateSettingsStepTests extends ESSingleNodeTestCase {

    private static final SettingsTestingService service = new SettingsTestingService();

    public static class SettingsListenerPlugin extends Plugin {

        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(SettingsTestingService.VALUE);
        }

        @Override
        public void onIndexModule(IndexModule module) {
            module.addSettingsUpdateConsumer(SettingsTestingService.VALUE, service::setValue, service::validate);
        }

        @Override
        public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
                                                   ResourceWatcherService resourceWatcherService, ScriptService scriptService,
                                                   NamedXContentRegistry xContentRegistry, Environment environment,
                                                   NodeEnvironment nodeEnvironment, NamedWriteableRegistry namedWriteableRegistry,
                                                   IndexNameExpressionResolver expressionResolver) {
            return Collections.singletonList(service);
        }

    }
    public static class SettingsListenerModule extends AbstractModule {

        private final SettingsTestingService service;
        SettingsListenerModule(SettingsTestingService service) {
            this.service = service;
        }

        @Override
        protected void configure() {
            bind(SettingsTestingService.class).toInstance(service);
        }

    }
    static class SettingsTestingService {

        public static final String INVALID_VALUE = "INVALID";
        static Setting<String> VALUE = Setting.simpleString("index.test.setting", Property.Dynamic, Property.IndexScope);
        public volatile String value;

        void setValue(String value) {
            this.value = value;
        }

        void validate(String value) {
            if (value.equals(INVALID_VALUE)) {
                throw new IllegalArgumentException("[" + INVALID_VALUE + "] is not supported");
            }
        }

        void resetValues() {
            this.value = "";
        }

    }
    @After
    public void resetSettingValue() {
        service.resetValues();
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singletonList(SettingsListenerPlugin.class);
    }

    public void testUpdateSettingsStepRetriesOnError() throws InterruptedException {
        assertAcked(client().admin().indices().prepareCreate("test").setSettings(Settings.builder()
            .build()).get());

        ClusterService clusterService = getInstanceFromNode(ClusterService.class);
        ClusterState state = clusterService.state();
        IndexMetaData indexMetaData = state.metaData().index("test");
        ThreadPool threadPool = getInstanceFromNode(ThreadPool.class);
        ClusterStateObserver observer = new ClusterStateObserver(clusterService, null, logger, threadPool.getThreadContext());

        CountDownLatch latch = new CountDownLatch(2);

        // fail the first setting update by using an invalid valid
        Settings invalidValueSetting = Settings.builder().put("index.test.setting", INVALID_VALUE).build();
        UpdateSettingsStep step = new UpdateSettingsStep(
            new StepKey("hot", "action", "updateSetting"), new StepKey("hot", "action", "validate"), client(),
            invalidValueSetting);

        step.performAction(indexMetaData, state, observer, new AsyncActionStep.Listener() {
            @Override
            public void onResponse(boolean complete) {
                latch.countDown();
                fail("expected the test to fail when updating the setting to an invalid value");
            }

            @Override
            public void onFailure(Exception e) {
                latch.countDown();

                // use a valid setting value so the second update call is successful
                Settings validIndexSetting = Settings.builder().put("index.test.setting", "valid").build();
                UpdateSettingsStep step = new UpdateSettingsStep(
                    new StepKey("hot", "action", "updateSetting"), new StepKey("hot", "action", "validate"), client(),
                    validIndexSetting);

                step.performAction(indexMetaData, state, observer, new AsyncActionStep.Listener() {
                    @Override
                    public void onResponse(boolean complete) {
                        latch.countDown();
                        assertThat(complete, is(true));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        latch.countDown();
                        fail("unexpected failure when trying to update setting to a valid value");
                    }
                });
            }
        });

        latch.await(10, TimeUnit.SECONDS);

        SettingsTestingService instance = getInstanceFromNode(SettingsTestingService.class);
        assertThat(instance.value, is("valid"));
    }
}
