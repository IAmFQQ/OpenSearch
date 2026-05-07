/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index;

import org.opensearch.common.annotation.ExperimentalApi;

import java.util.Locale;

/**
 * Index mode that controls default settings for different use cases.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
public enum IndexMode {

    STANDARD,
    LOGSDB;

    public static IndexMode fromString(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
