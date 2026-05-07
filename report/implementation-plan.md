# OpenSearch LogsDB 实施方案

> 原则：以 OpenSearch 3.6 现有代码为基础，最小化新代码，聚焦存储收益
> 不是拷贝 ES 的历史迭代，而是借鉴其实现思想

---

## 总览：分阶段收益

```
Phase 0（0 行代码）：   配置即用           → 约 -50~60% 存储
Phase 1（~365 行）：   3 个关键缺口        → 约 -70~74% 存储
Phase 2（~100 行）：   route_on_sort       → 约 -70~77% 存储
Phase 3（长期）：      TSDB codec / pattern → 超过 -77%
```

---

## Phase 0：配置即用（零代码）

**立即可做**，OpenSearch 3.6 现有功能已可达 50-60% 存储收益。

```json
PUT _index_template/logs-optimized
{
  "index_patterns": ["logs-*"],
  "template": {
    "settings": {
      "index.derived_source.enabled": true,
      "index.sort.field": ["host.name", "@timestamp"],
      "index.sort.order": ["asc", "desc"],
      "index.codec": "best_compression"
    },
    "mappings": {
      "properties": {
        "message":    { "type": "match_only_text" },
        "@timestamp": { "type": "date" },
        "host.name":  { "type": "keyword" }
      }
    }
  }
}
```

**收益分解**：`derived_source`（-35%） + `index.sort`（-20%） + `match_only_text`（-5%） + `best_compression` DEFLATE（-10%）≈ **-50~60%**

---

## Phase 1：三个关键代码缺口

**总代码量**：约 365 行
**额外收益**：在 Phase 0 基础上再获得 -12~14%

---

### P1：ZSTD Stored Fields（约 200 行）

**原理**：替换 `best_compression` 中的 DEFLATE 为 ZSTD，存储 -12% 且吞吐 +14%

**依赖**：`com.github.luben:zstd-jni:1.5.6-1` 版本号在 `gradle/libs.versions.toml:90`，artifact 声明在 `libs/compress/build.gradle:24`，无新依赖。

> ES 用 Panama FFM (Java 21+)；OpenSearch 用已有的 `zstd-jni`，效果等同，无需额外引入。

#### 新建 `ZstdStoredFieldsFormat.java`

```java
// server/src/main/java/org/opensearch/index/codec/ZstdStoredFieldsFormat.java
package org.opensearch.index.codec;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.apache.lucene.codecs.StoredFieldsFormat;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.codecs.lucene90.compressing.Lucene90CompressingStoredFieldsFormat;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.DataOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import java.io.IOException;

/**
 * ZSTD stored fields format，复用已有 zstd-jni 依赖。
 * 与 ES Zstd814StoredFieldsFormat.BEST_COMPRESSION 参数一致：level=3, 240KB block, 2048 docs。
 */
public final class ZstdStoredFieldsFormat extends StoredFieldsFormat {

    // 与 ES 8.16 BEST_COMPRESSION 参数对齐
    private static final int LEVEL = 3;
    private static final int BLOCK_SIZE = (256 - 16) * 1024;  // 240KB
    private static final int BLOCK_DOCS = 2048;

    private final Lucene90CompressingStoredFieldsFormat delegate;

    public ZstdStoredFieldsFormat() {
        this.delegate = new Lucene90CompressingStoredFieldsFormat(
            "ZstdStoredFields",
            new CompressionMode() {
                @Override public Compressor newCompressor() { return new ZstdCompressor(LEVEL); }
                @Override public Decompressor newDecompressor() { return DECOMPRESSOR; }
            },
            BLOCK_SIZE, BLOCK_DOCS, 1024
        );
    }

    @Override
    public StoredFieldsReader fieldsReader(Directory d, SegmentInfo si, FieldInfos fn, IOContext ctx)
        throws IOException { return delegate.fieldsReader(d, si, fn, ctx); }

    @Override
    public StoredFieldsWriter fieldsWriter(Directory d, SegmentInfo si, IOContext ctx)
        throws IOException { return delegate.fieldsWriter(d, si, ctx); }

    // Compressor：每个 segment 写入创建一次，ctx 复用，避免重复初始化
    private static class ZstdCompressor extends Compressor {
        private final ZstdCompressCtx ctx;
        ZstdCompressor(int level) { ctx = new ZstdCompressCtx(); ctx.setLevel(level); }

        @Override
        public void compress(byte[] bytes, int off, int len, DataOutput out) throws IOException {
            int maxLen = (int) com.github.luben.zstd.Zstd.compressBound(len);
            byte[] buf = new byte[maxLen];
            int compLen = (int) ctx.compressByteArray(buf, 0, maxLen, bytes, off, len);
            out.writeVInt(compLen);
            out.writeBytes(buf, 0, compLen);
        }
        @Override public void close() throws IOException { ctx.close(); }
    }

    // Decompressor：无状态，单例复用
    private static final Decompressor DECOMPRESSOR = new Decompressor() {
        @Override
        public void decompress(DataInput in, int origLen, int offset, int length, BytesRef bytes)
            throws IOException {
            int compLen = in.readVInt();
            byte[] comp = new byte[compLen];
            in.readBytes(comp, 0, compLen);
            if (bytes.bytes.length < origLen)
                bytes.bytes = new byte[ArrayUtil.oversize(origLen, 1)];
            try (var ctx = new ZstdDecompressCtx()) {
                ctx.decompressByteArray(bytes.bytes, 0, origLen, comp, 0, compLen);
            }
            bytes.offset = offset;
            bytes.length = length;
        }
        @Override public Decompressor clone() { return this; }
    };
}
```

#### 修改 `PerFieldMappingPostingFormatCodec`（约 10 行改动）

```java
// 新增字段
private final ZstdStoredFieldsFormat zstdStoredFields;

// 在构造函数中初始化
this.zstdStoredFields = (compressionMode == Mode.BEST_COMPRESSION)
    ? new ZstdStoredFieldsFormat() : null;

// 覆盖 storedFieldsFormat()
@Override
public StoredFieldsFormat storedFieldsFormat() {
    return zstdStoredFields != null ? zstdStoredFields : super.storedFieldsFormat();
}
```

---

### P2：`index.mode: logsdb`（约 115 行）

**原则**：不新建框架，全部基于 OpenSearch 3.6 现有扩展点。

**扩展点已有**：
- `IndexSettingProvider` 接口（`IndexSettingProvider.java`）
- `MetadataCreateIndexService.addAdditionalIndexSettingProvider()` 已存在
- `PerFieldMappingPostingFormatCodec` 可传入额外参数

#### 1. `IndexMode.java`（~15 行，新建）

```java
package org.opensearch.index;

public enum IndexMode {
    STANDARD,
    LOGSDB;

    public static IndexMode fromString(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
```

#### 2. `IndexSettings.java`（~15 行，修改）

```java
public static final Setting<IndexMode> INDEX_MODE = new Setting<>(
    "index.mode",
    IndexMode.STANDARD.name(),
    IndexMode::fromString,
    Property.IndexScope, Property.Final
);

public IndexMode getIndexMode() {
    return INDEX_MODE.get(settings);
}

public boolean isLogsDb() {
    return getIndexMode() == IndexMode.LOGSDB;
}
```

#### 3. `MetadataCreateIndexService.java`（~30 行，修改）

在 `prepareIndexSettings()` 中检测 logsdb 模式并注入默认配置：

```java
private Settings applyIndexModeDefaults(Settings requestSettings) {
    String modeStr = requestSettings.get(IndexSettings.INDEX_MODE.getKey(), IndexMode.STANDARD.name());
    if (!IndexMode.LOGSDB.name().equalsIgnoreCase(modeStr)) return requestSettings;

    Settings.Builder b = Settings.builder().put(requestSettings);
    // 自动启用 derived_source（最大单点收益）
    b.putIfAbsent(IndexSettings.INDEX_DERIVED_SOURCE_SETTING.getKey(), "true");
    // 自动使用 ZSTD（P1 完成后生效）
    b.putIfAbsent(EngineConfig.INDEX_CODEC_SETTING.getKey(), CodecService.BEST_COMPRESSION_CODEC);
    // 自动注入 host-first 排序（如果用户未配置）
    if (!requestSettings.hasValue(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey())) {
        b.putList(IndexSortConfig.INDEX_SORT_FIELD_SETTING.getKey(), "host.name", "@timestamp");
        b.putList(IndexSortConfig.INDEX_SORT_ORDER_SETTING.getKey(), "asc", "desc");
    }
    return b.build();
}
```

#### 4. `LogsIndexModeSettingsProvider.java`（~40 行，新建，实现已有接口）

```java
/**
 * 自动将 logs-*-* 数据流的新索引注入 index.mode=logsdb。
 * 实现 OpenSearch 已有的 IndexSettingProvider 接口，注册到 MetadataCreateIndexService。
 *
 * 参考 ES LogsdbIndexModeSettingsProvider 的核心思路，但大幅简化：
 * 不做 mapping hints 检测，不做 license 检查，只做模式匹配。
 */
public class LogsIndexModeSettingsProvider implements IndexSettingProvider {

    private final boolean logsdbEnabled;

    public LogsIndexModeSettingsProvider(Settings nodeSettings) {
        // 节点级开关，默认开启
        this.logsdbEnabled = nodeSettings.getAsBoolean("cluster.logsdb.enabled", true);
    }

    @Override
    public Settings getAdditionalIndexSettings(String indexName, boolean isDataStreamIndex,
                                                Settings templateAndRequestSettings) {
        if (!logsdbEnabled) return Settings.EMPTY;
        if (!isDataStreamIndex) return Settings.EMPTY;
        // 用户已显式设置 index.mode，不干预
        if (templateAndRequestSettings.hasValue(IndexSettings.INDEX_MODE.getKey())) return Settings.EMPTY;

        // 从 index name 推断 data stream name（data stream index 格式 .ds-<name>-<date>-N）
        String dataStreamName = extractDataStreamName(indexName);
        if (dataStreamName == null || !isLogsPattern(dataStreamName)) return Settings.EMPTY;

        return Settings.builder()
            .put(IndexSettings.INDEX_MODE.getKey(), IndexMode.LOGSDB.name())
            .build();
    }

    // logs-<type>-<namespace> 三段格式
    private static boolean isLogsPattern(String name) {
        int first = name.indexOf('-');
        if (first < 0) return false;
        return name.startsWith("logs-") && name.indexOf('-', first + 1) > first + 1;
    }
}
```

#### 5. `PerFieldMappingPostingFormatCodec`：logsdb 模式启用 Lucene104 默认 dvFormat（~15 行）

```java
// 传入 IndexMode（构造函数增加参数）
private final IndexMode indexMode;
private final DocValuesFormat dvFormat = new Lucene90DocValuesFormat();       // 默认保持兼容
private final DocValuesFormat lucene104DvFormat = super.docValuesFormat();    // Lucene104 默认，写 skip list

public PerFieldMappingPostingFormatCodec(Mode compressionMode, MapperService mapperService,
                                          Logger logger, IndexMode indexMode) {
    // ...
    this.indexMode = indexMode;
}

@Override
public DocValuesFormat getDocValuesFormatForField(String field) {
    // logsdb 模式：使用 Lucene104 默认格式（写 DocValuesSkipper，支持范围查询加速）
    if (indexMode == IndexMode.LOGSDB) return lucene104DvFormat;
    return dvFormat;  // 其他模式保持 Lucene90 兼容
}
```

> **注意**：修改 dvFormat 会影响 segment 格式。需要在 backward-codecs 模块添加旧格式的读支持，或仅对 logsdb 新索引生效（通过 IndexMode 控制已满足此条件）。

---

### P3：`_seq_no` 优化（约 50 行）

**原理**：用 `NumericDocValuesField.indexedField()` 替代 `LongPoint + NumericDocValuesField`，消除独立 BKD 文件，保留范围查询（skip index 内置在 doc values 文件中）。

**已有先例**：`NumberFieldMapper` 在 `skiplist=true` 时已用 `SortedNumericDocValuesField.indexedField()`（`NumberFieldMapper:644-649`）——同一 Lucene 10 API，确认在 3.6 中可用，但需显式传入 `skiplist=true` 参数触发该路径。

#### 修改 `SeqNoFieldMapper.java`

```java
// 添加设置（logsdb 模式自动触发）
public static final Setting<Boolean> SEQ_NO_DOC_VALUES_ONLY = Setting.boolSetting(
    "index.seq_no.doc_values_only", false, Property.IndexScope, Property.Final
);

// 修改 SequenceIDFields 构造
public SequenceIDFields(boolean docValuesOnly, boolean isTombstone) {
    if (docValuesOnly) {
        // Lucene 10 indexed doc values：内置 skip index，消除独立 BKD (.dim/.dii) 文件
        // 注意：NumericDocValuesField.indexedField() 而非 SortedNumericDocValuesField.indexedField()
        // seq_no 是单值字段（NumericDocValues），不是多值字段（SortedNumericDocValues）
        this.seqNo = NumericDocValuesField.indexedField(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO);
        this.seqNoDocValue = null;  // 合并到 seqNo，不再单独需要
    } else {
        this.seqNo = new LongPoint(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO);
        this.seqNoDocValue = new NumericDocValuesField(NAME, SequenceNumbers.UNASSIGNED_SEQ_NO);
    }
    // primaryTerm 和 tombstone 不变
    this.primaryTerm = new NumericDocValuesField(PRIMARY_TERM_NAME, 0);
    this.tombstoneField = isTombstone ? new NumericDocValuesField(TOMBSTONE_NAME, 1) : null;
}
```

在 `MetadataCreateIndexService.applyIndexModeDefaults()` 中添加：
```java
// logsdb 模式下自动启用 _seq_no 优化
b.putIfAbsent(SeqNoFieldMapper.SEQ_NO_DOC_VALUES_ONLY.getKey(), "true");
```

---

## Phase 2：`route_on_sort_fields`（约 100 行）

**预期收益**：额外 -20% 存储，代价是 1-4% 摄取损耗

**设计原则**：OpenSearch 不需要复制 ES 的"把哈希嵌入 `_id` 字节"的设计——那是 ES 为了保持 `_id` 格式语义一致性的历史包袱。我们直接修改路由哈希函数更简洁。

#### 新增设置

```java
// IndexSettings.java
public static final Setting<Boolean> LOGSDB_ROUTE_ON_SORT_FIELDS = Setting.boolSetting(
    "index.logsdb.route_on_sort_fields", false, Property.IndexScope, Property.Final
);
```

#### 修改 `IndexRouting.java`

```java
// 在现有路由逻辑基础上，logsdb 模式下使用排序字段哈希
public int indexShard(String id, String routing, IndexMetadata indexMetadata, ParsedDocument doc) {
    if (shouldRouteOnSortFields(indexMetadata)) {
        String routingKey = buildSortFieldRoutingKey(doc, indexMetadata.getIndexSortConfig());
        if (!routingKey.isEmpty()) {
            return Math.floorMod(Murmur3HashFunction.hash(routingKey), indexMetadata.getNumberOfShards());
        }
        // 回退到默认路由（排序字段缺失时）
    }
    return Math.floorMod(Murmur3HashFunction.hash(routing != null ? routing : id),
                          indexMetadata.getNumberOfShards());
}

private String buildSortFieldRoutingKey(ParsedDocument doc, IndexSortConfig sortConfig) {
    if (sortConfig == null) return "";
    StringBuilder key = new StringBuilder();
    for (String field : sortConfig.getSortFields()) {
        if ("@timestamp".equals(field)) break;  // 时间戳不参与路由键（粒度太细）
        IndexableField f = doc.rootDoc().getField(field);
        if (f != null) key.append(f.stringValue()).append('\0');
    }
    return key.toString();
}
```

**约束**：
- 要求排序字段中至少有一个非 `@timestamp` 字段（否则路由无意义）
- 现有索引需 reindex 才能生效
- 需文档说明：启用后不应提供自定义 `routing`

---

## Phase 3：长期优化

以下功能复杂度高，建议在 Phase 1+2 落地验证后再推进：

### TSDB 数值 Doc Values Codec

**预期收益**：已排序数值列 -60~90%（如 `kubernetes.pod.name` 110MB→7MB）

**思路**：为 logsdb 模式的 `PerFieldMappingPostingFormatCodec` 注入自定义 `DocValuesConsumer`，对数值列应用 RLE + PFOR-delta 编码。

**代码量估算**：约 1500-2000 行（需要自定义 DocValuesFormat 全栈实现）

**建议**：参考 ES `ES819TSDBDocValuesFormat`，不全量移植，只实现数值列的 RLE/PFOR-delta 部分。

### `pattern_text` 字段类型

**正确理解 ES 实现**：不是 Drain 算法，是简单规则——**token 中含数字则为变量，否则为模板词**。

```java
static boolean isArg(String token) {
    for (char c : token.toCharArray()) if (Character.isDigit(c)) return true;
    return false;
}
```

存储：4 个 doc values 字段（template + template_id + args_info + args）。读取时按位置重组。

**代码量估算**：约 500 行（新建 FieldMapper + 4 个 doc values 字段 + synthetic source loader）

**OpenSearch 优势**：不受 ES x-pack 企业授权限制，可以全开源实现。

### `derived_source` 字段覆盖扩展

可以在日志场景有需求时逐步添加：
- `range` 字段类型：通过 stored field fallback（~50 行）
- `flat_object` 字段类型：通过 stored field fallback（~50 行）

---

## 实施时间线

```
2026 Q2（1-2 个月）：
  Phase 1：ZSTD + logsdb mode + _seq_no
  约 365 行代码，达到 -70~74% 存储收益

2026 Q3（2-3 个月）：
  Phase 2：route_on_sort_fields
  约 100 行代码，达到 -70~77% 存储收益

2026 Q4+（长期）：
  Phase 3：TSDB codec、pattern_text
  大幅提升数值列和 message 字段的压缩比
```

---

## 文件修改汇总

| 文件 | 操作 | 新增行数 |
|------|------|---------|
| `server/.../codec/ZstdStoredFieldsFormat.java` | 新建 | ~150 行 |
| `server/.../codec/PerFieldMappingPostingFormatCodec.java` | 修改 | ~30 行 |
| `server/.../index/IndexMode.java` | 新建 | ~15 行 |
| `server/.../index/IndexSettings.java` | 修改 | ~20 行 |
| `server/.../cluster/metadata/MetadataCreateIndexService.java` | 修改 | ~35 行 |
| `server/.../index/LogsIndexModeSettingsProvider.java` | 新建 | ~40 行 |
| `server/.../mapper/SeqNoFieldMapper.java` | 修改 | ~50 行 |
| `server/.../cluster/routing/IndexRouting.java` | 修改（Phase 2） | ~60 行 |
| `server/.../index/IndexSettings.java`（route 设置） | 修改（Phase 2） | ~10 行 |

**Phase 1 总计：~340 行**，全部基于现有扩展点，零新依赖。
