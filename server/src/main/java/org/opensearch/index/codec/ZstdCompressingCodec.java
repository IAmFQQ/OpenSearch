/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.codec;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.codecs.DocValuesFormat;
import org.apache.lucene.codecs.FieldInfosFormat;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.LiveDocsFormat;
import org.apache.lucene.codecs.NormsFormat;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.SegmentInfoFormat;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.TermVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat;
import org.apache.lucene.codecs.perfield.PerFieldDocValuesFormat;
import org.apache.lucene.codecs.perfield.PerFieldPostingsFormat;
import org.opensearch.index.codec.fuzzy.FuzzyFilterPostingsFormat;
import org.opensearch.index.codec.fuzzy.FuzzySetFactory;
import org.opensearch.index.codec.fuzzy.FuzzySetParameters;
import org.opensearch.index.mapper.IdFieldMapper;
import org.opensearch.index.mapper.MapperService;

import java.util.Map;

/**
 * A codec that uses ZSTD compression for stored fields while preserving per-field
 * postings and doc values format overrides via {@link MapperService}.
 * <p>
 * Cannot extend {@link Lucene104Codec} because its {@code storedFieldsFormat()} is final.
 * Instead, this codec provides custom {@link PerFieldPostingsFormat} and
 * {@link PerFieldDocValuesFormat} implementations backed by MapperService.
 *
 * @opensearch.internal
 */
public final class ZstdCompressingCodec extends Codec {

    private final Lucene104Codec delegate;
    private final ZstdStoredFieldsFormat zstdStoredFields;
    private final PostingsFormat perFieldPostings;
    private final DocValuesFormat perFieldDocValues;
    private final KnnVectorsFormat perFieldKnnVectors;

    /**
     * No-arg constructor for SPI (used when reading segments via {@link Codec#forName}).
     */
    public ZstdCompressingCodec() {
        this(null, null);
    }

    public ZstdCompressingCodec(MapperService mapperService, Logger logger) {
        super("ZstdCompressingCodec");
        this.delegate = new Lucene104Codec(Lucene104Codec.Mode.BEST_COMPRESSION);
        this.zstdStoredFields = new ZstdStoredFieldsFormat();
        this.perFieldPostings = new MapperServicePerFieldPostingsFormat(mapperService, logger, delegate);
        this.perFieldDocValues = new MapperServicePerFieldDocValuesFormat();
        this.perFieldKnnVectors = delegate.knnVectorsFormat();
    }

    @Override
    public StoredFieldsFormat storedFieldsFormat() {
        return zstdStoredFields;
    }

    @Override
    public PostingsFormat postingsFormat() {
        return perFieldPostings;
    }

    @Override
    public DocValuesFormat docValuesFormat() {
        return perFieldDocValues;
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return perFieldKnnVectors;
    }

    @Override
    public TermVectorsFormat termVectorsFormat() {
        return delegate.termVectorsFormat();
    }

    @Override
    public FieldInfosFormat fieldInfosFormat() {
        return delegate.fieldInfosFormat();
    }

    @Override
    public SegmentInfoFormat segmentInfoFormat() {
        return delegate.segmentInfoFormat();
    }

    @Override
    public NormsFormat normsFormat() {
        return delegate.normsFormat();
    }

    @Override
    public LiveDocsFormat liveDocsFormat() {
        return delegate.liveDocsFormat();
    }

    @Override
    public CompoundFormat compoundFormat() {
        return delegate.compoundFormat();
    }

    @Override
    public PointsFormat pointsFormat() {
        return delegate.pointsFormat();
    }

    /**
     * {@link PerFieldPostingsFormat} delegating to
     * {@link PerFieldMappingPostingFormatCodec#resolvePostingsFormat} for field-level
     * postings format selection, avoiding code duplication.
     */
    private static final class MapperServicePerFieldPostingsFormat extends PerFieldPostingsFormat {

        private final MapperService mapperService;
        private final Logger logger;
        private final FuzzySetFactory fuzzySetFactory;
        private PostingsFormat docIdPostingsFormat;

        MapperServicePerFieldPostingsFormat(MapperService mapperService, Logger logger, Lucene104Codec fallback) {
            this.mapperService = mapperService;
            this.logger = logger;
            this.fuzzySetFactory = mapperService != null
                ? new FuzzySetFactory(
                    Map.of(
                        IdFieldMapper.NAME,
                        new FuzzySetParameters(() -> mapperService.getIndexSettings().getDocIdFuzzySetFalsePositiveProbability())
                    )
                )
                : null;
        }

        @Override
        public PostingsFormat getPostingsFormatForField(String field) {
            PostingsFormat resolved = PerFieldMappingPostingFormatCodec.resolvePostingsFormat(field, mapperService, logger);
            if (resolved != null) {
                return resolved;
            }
            if (docIdPostingsFormat == null) {
                docIdPostingsFormat = new FuzzyFilterPostingsFormat(
                    PostingsFormat.forName("Lucene104"), fuzzySetFactory
                );
            }
            return docIdPostingsFormat;
        }
    }

    /**
     * {@link PerFieldDocValuesFormat} that uses {@link Lucene90DocValuesFormat}
     * for all fields.
     */
    private static final class MapperServicePerFieldDocValuesFormat extends PerFieldDocValuesFormat {

        private final DocValuesFormat dvFormat = new Lucene90DocValuesFormat();

        @Override
        public DocValuesFormat getDocValuesFormatForField(String field) {
            return dvFormat;
        }
    }
}
