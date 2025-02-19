/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.idp.saml.sp;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.idp.IdentityProviderPlugin;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.idp.saml.idp.SamlIdentityProviderBuilder.IDP_ENTITY_ID;
import static org.elasticsearch.xpack.idp.saml.idp.SamlIdentityProviderBuilder.IDP_SSO_REDIRECT_ENDPOINT;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.opensaml.saml.saml2.core.NameIDType.TRANSIENT;

public class SamlServiceProviderIndexTests extends ESSingleNodeTestCase {

    private ClusterService clusterService;
    private SamlServiceProviderIndex serviceProviderIndex;

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.unmodifiableList(Arrays.asList(LocalStateCompositeXPackPlugin.class, IdentityProviderPlugin.class));
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
            .put(IDP_ENTITY_ID.getKey(), "urn:idp:org")
            .put(IDP_SSO_REDIRECT_ENDPOINT.getKey(), "https://idp.org/sso/init")
            .put(super.nodeSettings())
            .build();
    }

    @Before
    public void setupComponents() throws Exception {
        clusterService = super.getInstanceFromNode(ClusterService.class);
        serviceProviderIndex = new SamlServiceProviderIndex(client(), clusterService);
    }

    @After
    public void deleteTemplateAndIndex() {
        client().admin().indices().delete(new DeleteIndexRequest(SamlServiceProviderIndex.INDEX_NAME + "*")).actionGet();
        client().admin().indices().deleteTemplate(new DeleteIndexTemplateRequest(SamlServiceProviderIndex.TEMPLATE_NAME)).actionGet();
        serviceProviderIndex.close();
    }

    public void testWriteAndFindServiceProvidersFromIndex() {
        final int count = randomIntBetween(3, 5);
        List<SamlServiceProviderDocument> documents = new ArrayList<>(count);

        final ClusterService clusterService = super.getInstanceFromNode(ClusterService.class);
        // Install the template
        assertTrue("Template should have been installed", installTemplate());
        // No need to install it again
        assertFalse("Template should not have been installed a second time", installTemplate());

        // Index should not exist yet
        assertThat(clusterService.state().metaData().index(SamlServiceProviderIndex.INDEX_NAME), nullValue());

        for (int i = 0; i < count; i++) {
            final SamlServiceProviderDocument doc = randomDocument(i);
            writeDocument(doc);
            documents.add(doc);
        }

        final IndexMetaData indexMetaData = clusterService.state().metaData().index(SamlServiceProviderIndex.INDEX_NAME);
        assertThat(indexMetaData, notNullValue());
        assertThat(indexMetaData.getSettings().get("index.format"), equalTo("1"));
        assertThat(indexMetaData.getAliases().size(), equalTo(1));
        assertThat(indexMetaData.getAliases().keys().toArray(), arrayContainingInAnyOrder(SamlServiceProviderIndex.ALIAS_NAME));

        refresh();

        final Set<SamlServiceProviderDocument> allDocs = getAllDocs();
        assertThat(allDocs, iterableWithSize(count));
        for (SamlServiceProviderDocument doc : documents) {
            assertThat(allDocs, hasItem(Matchers.equalTo(doc)));
        }

        final SamlServiceProviderDocument readDoc = randomFrom(documents);
        assertThat(readDocument(readDoc.docId), equalTo(readDoc));

        final SamlServiceProviderDocument findDoc = randomFrom(documents);
        assertThat(findByEntityId(findDoc.entityId), equalTo(findDoc));

        final SamlServiceProviderDocument deleteDoc = randomFrom(documents);
        final DeleteResponse deleteResponse = deleteDocument(deleteDoc);
        assertThat(deleteResponse.getId(), equalTo(deleteDoc.docId));
        assertThat(deleteResponse.getResult(), equalTo(DocWriteResponse.Result.DELETED));

        refresh();

        assertThat(readDocument(deleteDoc.docId), nullValue());
        assertThat(findAllByEntityId(deleteDoc.entityId), emptyIterable());
    }

    public void testWritesViaAliasIfItExists() {
        final PlainActionFuture<Boolean> installTemplate = new PlainActionFuture<>();
        serviceProviderIndex.installIndexTemplate(installTemplate);
        assertTrue(installTemplate.actionGet());

        // Create an index that will trigger the template, but isn't the standard index name
        final String customIndexName = SamlServiceProviderIndex.INDEX_NAME + "-test";
        client().admin().indices().create(new CreateIndexRequest(customIndexName)).actionGet();

        final IndexMetaData indexMetaData = clusterService.state().metaData().index(customIndexName);
        assertThat(indexMetaData, notNullValue());
        assertThat(indexMetaData.getSettings().get("index.format"), equalTo("1"));
        assertThat(indexMetaData.getAliases().size(), equalTo(1));
        assertThat(indexMetaData.getAliases().keys().toArray(), arrayContainingInAnyOrder(SamlServiceProviderIndex.ALIAS_NAME));

        SamlServiceProviderDocument document = randomDocument(1);
        writeDocument(document);

        // Index should not exist because we created an alternate index, and the alias points to that.
        assertThat(clusterService.state().metaData().index(SamlServiceProviderIndex.INDEX_NAME), nullValue());

        refresh();

        final Set<SamlServiceProviderDocument> allDocs = getAllDocs();
        assertThat(allDocs, iterableWithSize(1));
        assertThat(allDocs, hasItem(Matchers.equalTo(document)));

        assertThat(readDocument(document.docId), equalTo(document));
    }

    public void testInstallTemplateAutomaticallyOnClusterChange() throws Exception {
        // Create an index that will trigger a cluster state change
        final String indexName = randomAlphaOfLength(7).toLowerCase(Locale.ROOT);
        client().admin().indices().create(new CreateIndexRequest(indexName)).actionGet();

        ensureGreen(indexName);

        IndexTemplateMetaData templateMeta = clusterService.state().metaData().templates().get(SamlServiceProviderIndex.TEMPLATE_NAME);

        assertBusy(() -> assertThat("template should have been installed", templateMeta, notNullValue()));

        final PlainActionFuture<Boolean> installTemplate = new PlainActionFuture<>();
        serviceProviderIndex.installIndexTemplate(installTemplate);
        assertFalse("Template is already installed, should not install again", installTemplate.actionGet());
    }

    public void testInstallTemplateAutomaticallyOnDocumentWrite() {
        final SamlServiceProviderDocument doc = randomDocument(1);
        writeDocument(doc);

        assertThat(readDocument(doc.docId), equalTo(doc));

        IndexTemplateMetaData templateMeta = clusterService.state().metaData().templates().get(SamlServiceProviderIndex.TEMPLATE_NAME);
        assertThat("template should have been installed", templateMeta, notNullValue());

        final PlainActionFuture<Boolean> installTemplate = new PlainActionFuture<>();
        serviceProviderIndex.installIndexTemplate(installTemplate);
        assertFalse("Template is already installed, should not install again", installTemplate.actionGet());
    }

    private boolean installTemplate() {
        final PlainActionFuture<Boolean> installTemplate = new PlainActionFuture<>();
        serviceProviderIndex.installIndexTemplate(installTemplate);
        return installTemplate.actionGet();
    }

    private Set<SamlServiceProviderDocument> getAllDocs() {
        final PlainActionFuture<Set<SamlServiceProviderDocument>> future = new PlainActionFuture<>();
        serviceProviderIndex.findAll(ActionListener.wrap(
            set -> future.onResponse(set.stream().map(doc -> doc.document.get())
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet))),
            future::onFailure
        ));
        return future.actionGet();
    }

    private SamlServiceProviderDocument readDocument(String docId) {
        final PlainActionFuture<SamlServiceProviderIndex.DocumentSupplier> future = new PlainActionFuture<>();
        serviceProviderIndex.readDocument(docId, future);
        final SamlServiceProviderIndex.DocumentSupplier supplier = future.actionGet();
        return supplier == null ? null : supplier.getDocument();
    }

    private void writeDocument(SamlServiceProviderDocument doc) {
        final PlainActionFuture<DocWriteResponse> future = new PlainActionFuture<>();
        serviceProviderIndex.writeDocument(doc, DocWriteRequest.OpType.INDEX, WriteRequest.RefreshPolicy.WAIT_UNTIL, future);
        doc.setDocId(future.actionGet().getId());
    }

    private DeleteResponse deleteDocument(SamlServiceProviderDocument doc) {
        final PlainActionFuture<DeleteResponse> future = new PlainActionFuture<>();
        serviceProviderIndex.readDocument(doc.docId, ActionListener.wrap(
            info -> serviceProviderIndex.deleteDocument(info.version, WriteRequest.RefreshPolicy.IMMEDIATE, future),
            future::onFailure));
        return future.actionGet();
    }

    private SamlServiceProviderDocument findByEntityId(String entityId) {
        final Set<SamlServiceProviderDocument> docs = findAllByEntityId(entityId);
        assertThat(docs, iterableWithSize(1));
        return docs.iterator().next();
    }

    private Set<SamlServiceProviderDocument> findAllByEntityId(String entityId) {
        final PlainActionFuture<Set<SamlServiceProviderDocument>> future = new PlainActionFuture<>();
        serviceProviderIndex.findByEntityId(entityId, ActionListener.wrap(
            set -> future.onResponse(set.stream().map(doc -> doc.document.get())
                .collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet))),
            future::onFailure
        ));
        return future.actionGet();
    }

    private void refresh() {
        PlainActionFuture<Void> future = new PlainActionFuture<>();
        serviceProviderIndex.refresh(future);
        future.actionGet();
    }

    public static SamlServiceProviderDocument randomDocument() {
        return randomDocument(randomIntBetween(1, 999_999));
    }

    public static SamlServiceProviderDocument randomDocument(int index) {
        final SamlServiceProviderDocument document = new SamlServiceProviderDocument();
        document.setName(randomAlphaOfLengthBetween(5, 12));
        document.setEntityId(randomUri() + index);
        document.setAcs(randomUri("https") + index + "/saml/acs");

        document.setEnabled(randomBoolean());
        document.setCreatedMillis(System.currentTimeMillis() - TimeValue.timeValueDays(randomIntBetween(2, 90)).millis());
        document.setLastModifiedMillis(System.currentTimeMillis() - TimeValue.timeValueHours(randomIntBetween(1, 36)).millis());

        if (randomBoolean()) {
            document.setNameIdFormat(TRANSIENT);
        }
        if (randomBoolean()) {
            document.setAuthenticationExpiryMillis(TimeValue.timeValueMinutes(randomIntBetween(1, 15)).millis());
        }

        document.privileges.setResource("app:" + randomAlphaOfLengthBetween(3, 6) + ":" + Math.abs(randomLong()));
        final int roleCount = randomIntBetween(0, 4);
        final Set<String> roles = new HashSet<>();
        for (int i = 0; i < roleCount; i++) {
            roles.add(randomAlphaOfLengthBetween(3, 6) + ":(" + randomAlphaOfLengthBetween(3, 6) + ")");
        }
        document.privileges.setRolePatterns(roles);

        document.attributeNames.setPrincipal(randomUri());
        if (randomBoolean()) {
            document.attributeNames.setName(randomUri());
        }
        if (randomBoolean()) {
            document.attributeNames.setEmail(randomUri());
        }
        if (roles.isEmpty() == false) {
            document.attributeNames.setRoles(randomUri());
        }

        assertThat(document.validate(), nullValue());
        return document;
    }

    private static String randomUri() {
        return randomUri(randomFrom("urn", "http", "https"));
    }

    private static String randomUri(String scheme) {
        return scheme + "://" + randomAlphaOfLengthBetween(2, 6) + "."
            + randomAlphaOfLengthBetween(4, 8) + "." + randomAlphaOfLengthBetween(2, 4) + "/";
    }

}
