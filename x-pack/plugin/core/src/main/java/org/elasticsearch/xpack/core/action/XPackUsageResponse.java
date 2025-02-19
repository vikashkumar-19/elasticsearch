/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.action;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.core.XPackFeatureSet;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class XPackUsageResponse extends ActionResponse {

    private final List<XPackFeatureSet.Usage> usages;

    public XPackUsageResponse(final List<XPackFeatureSet.Usage> usages) {
        this.usages = Objects.requireNonNull(usages);
    }

    public XPackUsageResponse(final StreamInput in) throws IOException {
        usages = in.readNamedWriteableList(XPackFeatureSet.Usage.class);
    }

    public List<XPackFeatureSet.Usage> getUsages() {
        return usages;
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        // we can only write the usages with version the coordinating node is compatible with otherwise it will not know the named writeable
        final List<XPackFeatureSet.Usage> usagesToWrite = Collections.unmodifiableList(usages
            .stream()
            .filter(usage -> out.getVersion().onOrAfter(usage.getMinimalSupportedVersion()))
            .collect(Collectors.toList()));
        writeTo(out, usagesToWrite);
    }

    private static void writeTo(final StreamOutput out, final List<XPackFeatureSet.Usage> usages) throws IOException {
        out.writeNamedWriteableList(usages);
    }

    }
