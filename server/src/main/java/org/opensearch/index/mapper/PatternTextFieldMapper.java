/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.mapper;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.opensearch.common.collect.Tuple;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.index.fielddata.plain.SortedSetOrdinalsIndexFieldData;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.aggregations.support.CoreValuesSourceType;
import org.opensearch.search.lookup.SearchLookup;
import org.opensearch.search.lookup.SourceLookup;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A field mapper that splits log message text into template words and variable arguments
 * using a simple digit-based heuristic: tokens containing any digit are classified as
 * arguments, all others are template words.
 * <p>
 * Storage:
 * <ul>
 *   <li>{@code <field>._template} — binary doc values holding template token positions and text</li>
 *   <li>{@code <field>._args} — binary doc values holding concatenated argument values</li>
 *   <li>{@code <field>._template_id} — numeric doc values holding a 64-bit hash of the template</li>
 *   <li>{@code <field>} — sorted set doc values for template token terms + indexed fields for fast term queries</li>
 * </ul>
 */
public class PatternTextFieldMapper extends ParametrizedFieldMapper {

    public static final String CONTENT_TYPE = "pattern_text";

    public static final TypeParser PARSER = new TypeParser((n, c) -> new Builder(n));

    private static final FieldType INDEXED_FIELD_TYPE = new FieldType();

    static {
        INDEXED_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
        INDEXED_FIELD_TYPE.setStored(false);
        INDEXED_FIELD_TYPE.setTokenized(false);
        INDEXED_FIELD_TYPE.setOmitNorms(true);
        INDEXED_FIELD_TYPE.freeze();
    }

    private PatternTextFieldMapper(String simpleName, PatternTextFieldType mappedFieldType) {
        super(simpleName, mappedFieldType, MultiFields.empty(), CopyTo.empty());
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public ParametrizedFieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName());
    }

    @Override
    protected void parseCreateField(ParseContext context) throws IOException {
        String text = context.parser().textOrNull();
        if (text == null || text.isEmpty()) {
            return;
        }

        PatternTextFieldType ft = (PatternTextFieldType) fieldType();
        Tuple<byte[], byte[]> encoded = PatternTextCodec.encode(text);

        context.doc().add(new BinaryDocValuesField(ft.templateDvField, new BytesRef(encoded.v1())));
        context.doc().add(new BinaryDocValuesField(ft.argsDvField, new BytesRef(encoded.v2())));
        context.doc().add(new NumericDocValuesField(ft.templateIdDvField, PatternTextCodec.templateHash(text)));

        String[] templateTokens = PatternTextCodec.templateTokens(text);
        for (String token : templateTokens) {
            BytesRef tokenRef = new BytesRef(token);
            context.doc().add(new SortedSetDocValuesField(fieldType().name(), tokenRef));
            context.doc().add(new Field(fieldType().name(), tokenRef, INDEXED_FIELD_TYPE));
        }
    }

    public static final class PatternTextFieldType extends SimpleMappedFieldType {

        final String templateDvField;
        final String argsDvField;
        final String templateIdDvField;

        PatternTextFieldType(String name, Map<String, String> meta) {
            super(name, true, false, true, TextSearchInfo.SIMPLE_MATCH_ONLY, meta);
            this.templateDvField = name + "._template";
            this.argsDvField = name + "._args";
            this.templateIdDvField = name + "._template_id";
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public ValueFetcher valueFetcher(QueryShardContext context, SearchLookup lookup, String format) {
            if (format != null) {
                throw new IllegalArgumentException(
                    "Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }
            return new PatternTextValueFetcher(name(), templateDvField, argsDvField);
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            BytesRef term = value instanceof BytesRef ? (BytesRef) value : new BytesRef(value.toString());
            return new TermQuery(new Term(name(), term));
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            return SortedSetDocValuesField.newSlowRangeQuery(
                name(), new BytesRef(""), new BytesRef("￿"), true, true);
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            failIfNoDocValues();
            return new SortedSetOrdinalsIndexFieldData.Builder(name(), CoreValuesSourceType.BYTES);
        }
    }

    /**
     * ValueFetcher that reconstructs the original text from pattern doc values,
     * avoiding dependency on _source (which is eliminated by derived_source).
     */
    private static final class PatternTextValueFetcher implements ValueFetcher {

        private final String fieldName;
        private final String templateDvField;
        private final String argsDvField;
        private BinaryDocValues templateValues;
        private BinaryDocValues argsValues;

        PatternTextValueFetcher(String fieldName, String templateDvField, String argsDvField) {
            this.fieldName = fieldName;
            this.templateDvField = templateDvField;
            this.argsDvField = argsDvField;
        }

        @Override
        public void setNextReader(LeafReaderContext context) {
            try {
                templateValues = context.reader().getBinaryDocValues(templateDvField);
                argsValues = context.reader().getBinaryDocValues(argsDvField);
            } catch (IOException e) {
                templateValues = null;
                argsValues = null;
            }
        }

        @Override
        public List<Object> fetchValues(SourceLookup lookup) throws IOException {
            if (templateValues != null && argsValues != null) {
                int docId = lookup.docId();
                if (docId >= 0 && templateValues.advanceExact(docId) && argsValues.advanceExact(docId)) {
                    BytesRef tpl = templateValues.binaryValue();
                    BytesRef args = argsValues.binaryValue();
                    if (tpl != null && args != null) {
                        String decoded = PatternTextCodec.decode(
                            tpl.bytes, tpl.offset, tpl.length,
                            args.bytes, args.offset, args.length
                        );
                        return Collections.singletonList(decoded);
                    }
                }
            }
            return Collections.emptyList();
        }
    }

    public static class Builder extends ParametrizedFieldMapper.Builder {
        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        Builder(String name) {
            super(name);
        }

        @Override
        public PatternTextFieldMapper build(BuilderContext context) {
            return new PatternTextFieldMapper(name, new PatternTextFieldType(buildFullName(context), meta.getValue()));
        }

        @Override
        protected List<Parameter<?>> getParameters() {
            return Collections.singletonList(meta);
        }
    }
}
