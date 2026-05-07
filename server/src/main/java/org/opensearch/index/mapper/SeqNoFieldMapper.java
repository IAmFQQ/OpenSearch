/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.opensearch.common.Nullable;
import org.opensearch.common.annotation.PublicApi;
import org.opensearch.common.settings.Setting;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.index.fielddata.IndexNumericFieldData.NumericType;
import org.opensearch.index.fielddata.plain.SortedNumericIndexFieldData;
import org.opensearch.index.mapper.ParseContext.Document;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.seqno.SequenceNumbers;
import org.opensearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Mapper for the {@code _seq_no} field.
 * <p>
 * We expect to use the seq# for sorting, during collision checking and for
 * doing range searches. Therefore the {@code _seq_no} field is stored both
 * as a numeric doc value and as numeric indexed field.
 * <p>
 * This mapper also manages the primary term field, which has no OpenSearch named
 * equivalent. The primary term is only used during collision after receiving
 * identical seq# values for two document copies. The primary term is stored as
 * a doc value field without being indexed, since it is only intended for use
 * as a key-value lookup.
 *
 * @opensearch.internal
 */
public class SeqNoFieldMapper extends MetadataFieldMapper {

    /**
     * A sequence ID, which is made up of a sequence number (both the searchable
     * and doc_value version of the field) and the primary term.
     *
     * @opensearch.api
     */
    @PublicApi(since = "1.0.0")
    public static class SequenceIDFields {

        public final Field seqNo;
        public final Field seqNoDocValue;
        public final Field primaryTerm;
        public final Field tombstoneField;

        public SequenceIDFields(Field seqNo, Field seqNoDocValue, Field primaryTerm, Field tombstoneField) {
            Objects.requireNonNull(seqNo, "sequence number field cannot be null");
            Objects.requireNonNull(primaryTerm, "primary term field cannot be null");
            this.seqNo = seqNo;
            this.seqNoDocValue = seqNoDocValue;
            this.primaryTerm = primaryTerm;
            this.tombstoneField = tombstoneField;
        }

        public static SequenceIDFields emptySeqID() {
            return new SequenceIDFields(
                new LongPoint(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO),
                new NumericDocValuesField(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO),
                new NumericDocValuesField(PRIMARY_TERM_NAME, 0),
                new NumericDocValuesField(TOMBSTONE_NAME, 0)
            );
        }

        public static SequenceIDFields emptySeqID(boolean docValuesOnly) {
            if (docValuesOnly) {
                // NumericDocValuesField.indexedField() creates a single field that provides
                // both indexed (skip index) and doc values, removing the need for a separate
                // NumericDocValuesField for _seq_no. _tombstone and _primary_term are kept.
                return new SequenceIDFields(
                    NumericDocValuesField.indexedField(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO),
                    null,
                    new NumericDocValuesField(PRIMARY_TERM_NAME, 0),
                    new NumericDocValuesField(TOMBSTONE_NAME, 0)
                );
            }
            return emptySeqID();
        }

    }

    public static final String NAME = "_seq_no";
    public static final String CONTENT_TYPE = "_seq_no";
    public static final String PRIMARY_TERM_NAME = "_primary_term";
    public static final String TOMBSTONE_NAME = "_tombstone";

    /**
     * When enabled, {@code _seq_no} uses a single {@link NumericDocValuesField#indexedField}
     * that stores both indexed and doc values in one field, eliminating the separate
     * {@link LongPoint} field. The {@code _tombstone} doc values field is also dropped.
     */
    public static final Setting<Boolean> SEQ_NO_DOC_VALUES_ONLY = Setting.boolSetting(
        "index.seq_no_doc_values_only",
        false,
        Setting.Property.IndexScope,
        Setting.Property.Final
    );

    public static final TypeParser PARSER = new FixedTypeParser(c -> {
        boolean docValuesOnly = c.getSettings().getAsBoolean(SEQ_NO_DOC_VALUES_ONLY.getKey(), false);
        return new SeqNoFieldMapper(docValuesOnly);
    });

    /**
     * Field type for internal sequence numbers
     *
     * @opensearch.internal
     */
    static final class SeqNoFieldType extends SimpleMappedFieldType {

        private static final SeqNoFieldType INSTANCE = new SeqNoFieldType();

        private SeqNoFieldType() {
            super(NAME, true, false, true, TextSearchInfo.SIMPLE_MATCH_ONLY, Collections.emptyMap());
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        private long parse(Object value) {
            if (value instanceof Number) {
                double doubleValue = ((Number) value).doubleValue();
                if (doubleValue < Long.MIN_VALUE || doubleValue > Long.MAX_VALUE) {
                    throw new IllegalArgumentException("Value [" + value + "] is out of range for a long");
                }
                if (doubleValue % 1 != 0) {
                    throw new IllegalArgumentException("Value [" + value + "] has a decimal part");
                }
                return ((Number) value).longValue();
            }
            if (value instanceof BytesRef) {
                value = ((BytesRef) value).utf8ToString();
            }
            return Long.parseLong(value.toString());
        }

        @Override
        public ValueFetcher valueFetcher(QueryShardContext context, SearchLookup lookup, String format) {
            throw new UnsupportedOperationException("Cannot fetch values for internal field [" + name() + "].");
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            long v = parse(value);
            return LongPoint.newExactQuery(name(), v);
        }

        @Override
        public Query termsQuery(List<?> values, @Nullable QueryShardContext context) {
            long[] v = new long[values.size()];
            for (int i = 0; i < values.size(); ++i) {
                v[i] = parse(values.get(i));
            }
            return LongPoint.newSetQuery(name(), v);
        }

        @Override
        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, QueryShardContext context) {
            long l = Long.MIN_VALUE;
            long u = Long.MAX_VALUE;
            if (lowerTerm != null) {
                l = parse(lowerTerm);
                if (includeLower == false) {
                    if (l == Long.MAX_VALUE) {
                        return new MatchNoDocsQuery();
                    }
                    ++l;
                }
            }
            if (upperTerm != null) {
                u = parse(upperTerm);
                if (includeUpper == false) {
                    if (u == Long.MIN_VALUE) {
                        return new MatchNoDocsQuery();
                    }
                    --u;
                }
            }
            return LongPoint.newRangeQuery(name(), l, u);
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            failIfNoDocValues();
            return new SortedNumericIndexFieldData.Builder(name(), NumericType.LONG);
        }
    }

    private final boolean docValuesOnly;

    public SeqNoFieldMapper() {
        this(false);
    }

    private SeqNoFieldMapper(boolean docValuesOnly) {
        super(SeqNoFieldType.INSTANCE);
        this.docValuesOnly = docValuesOnly;
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        // see InternalEngine.innerIndex to see where the real version value is set
        // also see ParsedDocument.updateSeqID (called by innerIndex)
        SequenceIDFields seqID = SequenceIDFields.emptySeqID(docValuesOnly);
        context.seqID(seqID);
        context.doc().add(seqID.seqNo);
        if (seqID.seqNoDocValue != null) {
            context.doc().add(seqID.seqNoDocValue);
        }
        context.doc().add(seqID.primaryTerm);
        if (seqID.tombstoneField != null) {
            context.doc().add(seqID.tombstoneField);
        }
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
        // In the case of nested docs, let's fill nested docs with the original
        // so that Lucene doesn't write a Bitset for documents that
        // don't have the field. This is consistent with the default value
        // for efficiency.
        // we share the parent docs fields to ensure good compression
        SequenceIDFields seqID = context.seqID();
        assert seqID != null;
        for (Document doc : context.nonRootDocuments()) {
            doc.add(seqID.seqNo);
            if (seqID.seqNoDocValue != null) {
                doc.add(seqID.seqNoDocValue);
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

}
