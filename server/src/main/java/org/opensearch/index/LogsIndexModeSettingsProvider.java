/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index;

import org.opensearch.common.settings.Settings;
import org.opensearch.index.shard.IndexSettingProvider;

/**
 * Automatically injects {@code index.mode=logsdb} for data streams matching the
 * {@code logs-*-*} pattern (e.g. {@code logs-nginx-production}).
 * <p>
 * Implements the existing {@link IndexSettingProvider} extension point, registered
 * via {@code MetadataCreateIndexService.addAdditionalIndexSettingProvider()}.
 *
 * @opensearch.experimental
 */
public class LogsIndexModeSettingsProvider implements IndexSettingProvider {

    private final boolean logsdbEnabled;

    public LogsIndexModeSettingsProvider(Settings nodeSettings) {
        this.logsdbEnabled = nodeSettings.getAsBoolean("cluster.logsdb.enabled", true);
    }

    @Override
    public Settings getAdditionalIndexSettings(String indexName, boolean isDataStreamIndex, Settings templateAndRequestSettings) {
        if (logsdbEnabled == false) {
            return Settings.EMPTY;
        }
        if (isDataStreamIndex == false) {
            return Settings.EMPTY;
        }
        // don't override user's explicit setting
        if (templateAndRequestSettings.hasValue(IndexSettings.INDEX_MODE.getKey())) {
            return Settings.EMPTY;
        }

        String dataStreamName = extractDataStreamName(indexName);
        if (dataStreamName == null || isLogsPattern(dataStreamName) == false) {
            return Settings.EMPTY;
        }

        return Settings.builder().put(IndexSettings.INDEX_MODE.getKey(), IndexMode.LOGSDB.name()).build();
    }

    /**
     * Extracts the data stream name from a data stream backing index name.
     * Data stream backing index format: {@code .ds-<data-stream-name>-<generation>-<source-uuid>}
     */
    static String extractDataStreamName(String indexName) {
        if (indexName == null || indexName.startsWith(".ds-") == false) {
            return null;
        }
        // .ds-logs-nginx-production-2026.05.07-000001
        //       ^-- data stream name starts here after ".ds-"
        int start = ".ds-".length();
        // find the last '-' before the generation suffix
        int lastDash = indexName.lastIndexOf('-');
        if (lastDash <= start) {
            return null;
        }
        int secondLastDash = indexName.lastIndexOf('-', lastDash - 1);
        if (secondLastDash <= start) {
            return null;
        }
        return indexName.substring(start, secondLastDash);
    }

    /**
     * Matches {@code logs-<type>-<namespace>} three-segment pattern.
     */
    static boolean isLogsPattern(String name) {
        if (name == null || name.startsWith("logs-") == false) {
            return false;
        }
        int first = name.indexOf('-');
        if (first < 0) {
            return false;
        }
        return name.indexOf('-', first + 1) > first + 1;
    }
}
