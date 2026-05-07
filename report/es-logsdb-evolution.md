# ES LogsDB 发展历程（2012–2026）

> 来源：PDF《ES LogsDB 发展历程：如何在不降低吞吐量的情况下，将索引大小减少多达 75%》
> 已结合 ES 9.4 源码（`/Users/qiafan/workspace/elasticsearch`）深度验证

---

## 背景：Elasticsearch 的写入开销

Elasticsearch 最初作为搜索引擎构建。每个索引文档会扩散到多个磁盘结构：

| 结构 | 用途 | 日志场景必要性 |
|------|------|--------------|
| **倒排索引** | 词条→文档映射，实现全文搜索 | ✅ 必须 |
| **`_source`** | 存储原始 JSON 块，用于 GET/fetch | ⚠️ 冗余（数据已在 doc values 中） |
| **Doc values** | 列式存储，用于排序和聚合 | ✅ 必须 |
| **BKD 树** | 数值/日期范围查询 | ✅ 必须 |

**核心问题**：同一字段值写入了 2–4 个不同磁盘结构，`_source` 中的数据是完全冗余存储。

---

## 为什么列式存储对压缩至关重要

Doc values 将 Lucene segment 内所有文档的每个字段存储为独立的列：

列式布局使压缩编解码器能逐列独立运行：

```
数值编解码器流水线：
原始 (RAW)      → [1706745600, 1706745700, 1706745900, 1706746200] (128 bits)
增量 (DELTA)    → [基准值, +100, +200, +300]                       (59 bits)
最大公约数 (GCD) → [基准值, 1, 2, 3]（除以公因子 100）             (39 bits)
位包装 (BIT-PACK)→ 将小整数打包进连续位存储                        (9 bits)
```

**关键**：这些编解码器只有在**相邻文档具有相关值**时才有效。这就是为什么索引排序至关重要。

---

## 技术演进时间线

### 基础阶段（2012–2017）

**Lucene 4.0（2012）**：引入 doc values
**Elasticsearch 5.0（2016）**：所有 keyword 和数值字段默认开启 doc values
**Lucene 7.0**：引入**稀疏 doc values**

---

### 渐进式提升（2020–2021）

**`match_only_text` 字段类型**
- 从倒排索引中去掉**词频（term frequencies）和位置信息（positions）**
- 字段级收益：倒排索引减少 ~40%
- 整体收益约 10%（当时 `_source`、doc values 问题更大）

---

### TSDB 转折点（2023 年 4 月，ES 8.7）

**合成 `_source`（Synthetic `_source`）**
- 写入时：完全跳过存储原始 JSON 块
- 读取时：从 doc values 逐字段读取，拼装成 JSON 返回

**实现架构**（源码验证）：
```java
// SourceLoader.java — Synthetic 实现
interface SyntheticFieldLoader {
    Stream<Map.Entry<String, StoredFieldLoader>> storedFieldLoaders(); // 声明依赖的 stored fields
    DocValuesLoader docValuesLoader(LeafReader reader, int[] docIds); // doc values 加载器
    boolean hasValue();
    void write(XContentBuilder b) throws IOException;
}

// 每个字段类型实现此接口：
// keyword → SortedSetDocValues
// number/date/ip → SortedNumericDocValues
// text → stored field fallback（需 store:true）
// 无法处理的字段 → _ignored_source（保存原始字节，并入合成结果）
```

**索引排序**（Index Sorting）
```
排序前（到达顺序）： api-01, web-02, db-01, api-01, web-01, ...
排序后（host-first）：api-01(×8块), api-02(×8块), db-01(×8块), ...
RLE 后：(api-01, 8), (api-02, 8), (db-01, 8), ...
```

合并效果：指标存储减少高达 70%

---

### TSDB 自定义编解码器（2024，ES 8.13/8.14）

专为已排序时序数据优化：RLE + PFOR-delta + 循环序号编码

实测：`kubernetes.pod.name` doc values：110 MB → 7.25 MB（减少 93%）

---

### LogsDB 技术预览（2024 年 8 月，ES 8.15）

整合所有功能到 `index.mode: logsdb`。

**关键实现**（源码验证）：
```java
// IndexMode.java — LOGSDB 枚举
LOGSDB("logsdb") {
    public SourceFieldMapper.Mode defaultSourceMode() { return SYNTHETIC; }     // 合成 source
    public String getDefaultCodec() { return BEST_COMPRESSION_CODEC; }          // ZSTD
    public boolean useTimeSeriesDocValuesCodec() { return true; }               // TSDB codec
    public CompressedXContent getDefaultMapping(IndexSettings s) {
        return s.logsdbAddHostNameField()                                        // 默认加 @timestamp (+ host.name)
            ? DEFAULT_MAPPING_TIMESTAMP_HOSTNAME
            : DEFAULT_MAPPING_TIMESTAMP;
    }
}
```

**自动识别数据流**（`LogsdbIndexModeSettingsProvider.java`）：
匹配 `logs-*-*` 模式的数据流自动注入 `index.mode=logsdb`，无需用户手动配置。

**排序顺序的重要性**：
```
host.name first → @timestamp：存储减少约 40%
@timestamp first → host.name：存储减少 ≤10%
原因：host-first 使共享字段值的文档聚集，是编解码器所需要的
```

---

### ZSTD 与正式发布（2024 年 11 月–12 月，ES 8.16–8.17）

**`best_compression` 永久切换到 ZSTD**

**实现细节**（源码验证）：
```java
// Zstd814StoredFieldsFormat.java
BEST_COMPRESSION(3, (256-16)*1024, 2048);  // level=3, 块大小240KB, 每块2048 doc

// 注意：ES 使用 Panama FFM API（java.lang.foreign，Java 21+）调用 native libzstd
// 不使用 zstd-jni 库
```

效果（极罕见的双赢）：
- 存储：额外 -12%
- 吞吐：额外 +14%

**ES 8.17 LogsDB 正式发布（GA）**，声称存储减少最高 65%。

---

### 路由与恢复（2025 年 4 月，ES 8.18）

**`route_on_sort_fields`**

**实现细节**（源码验证）：
- `LogsdbIndexModeSettingsProvider` 将排序字段（去掉 `@timestamp`）写入 `index.routing_path`
- `IndexRouting.ExtractFromSource.ForRoutingPath` 计算路由哈希
- **路由哈希嵌入 `_id` 字节本身**（offset `length-9`，4 字节）
- 排序字段中 `@timestamp` 不参与路由键计算

```
标准模式：    api-01(分片0,1,2混合), web-01(分片0,1,2混合)...
路由模式：    api-01(全在分片0), web-01(全在分片1)...
```

效果：额外 -20% 存储（代价：1-4% 摄取损耗）

**消除 `_recovery_source`**
- 新增 `LuceneSyntheticSourceChangesSnapshot`，副本恢复时直接从 doc values 重建文档
- 不再写入 `_recovery_source` stored field，只存储大小用于内存控制
- 效果：写入索引大小下降 39%

---

### 合并与恢复重构（2025 年 7 月，ES 9.1）

- 副本恢复完全切换到 synthetic source 重构，写入 I/O 减少约 50%
- 索引吞吐量比 8.17 基准提升约 19%
- 合并 CPU 减少约 40%
- **`_seq_no` 优化**（源码验证）：

```java
// SeqNoFieldMapper.java — 两种模式
case POINTS_AND_DOC_VALUES ->
    new LongPoint(NAME, ...) + new NumericDocValuesField(NAME, ...);   // 传统：BKD + doc values（两份）
case DOC_VALUES_ONLY ->
    NumericDocValuesField.indexedField(NAME, ...);                     // 新：仅有 indexed doc values（一份）
```

`NumericDocValuesField.indexedField()` 是 Lucene 10 新 API，在 doc values 中内置 skip index，替代独立 BKD 文件（`.dim/.dii`），实现 seq_no 存储减半，同时保留范围查询。

设置：`index.seq_no.index_options = DOC_VALUES_ONLY`（logsdb 自动启用）

---

### `pattern_text` 与 Failure Store（2025 年 10 月–2026 年 2 月，ES 9.2–9.3）

**`pattern_text` 字段类型**

> ⚠️ 注意：ES 不使用 Drain 算法，使用更简单的启发式规则：**token 中含数字 → 视为变量，否则为模板词**

```java
// PatternTextValueProcessor.java — 核心逻辑
static boolean isArg(String text) {
    for (int i = 0; i < text.length(); i++) {
        if (Character.isDigit(text.charAt(i))) return true;  // 含数字即为参数
    }
    return false;
}
```

写入时存储（4 个 doc values 字段）：
- `<field>.template`：模板字符串（SortedSetDocValues，多文档共享压缩极好）
- `<field>.template_id`：MurmurHash128 of template（keyword doc values，供排序/聚合）
- `<field>.args_info`：参数在模板中的位置（SortedSetDocValues）
- `<field>.args`：参数值（BinaryDocValues，空格分隔）

读取时按位置将变量插回模板重组原始消息。

> ⚠️ 注意：位于 `x-pack/plugin/logsdb/`，需要**企业授权**方可使用模板化功能

效果：高重复率日志 message 字段 -50%；自由格式低重复率日志效果有限

**`wildcard` 字段 ZSTD 压缩**
- 在 TSDB doc values codec（`ES819TSDBDocValuesFormat`）层面对 BinaryDocValues 使用 ZSTD level-1 块压缩
- 实测：URL 字段 2.92 GB → 1.12 GB（-62%）

**doc values skip lists**
- `@timestamp`、`host.name` 等字段启用 skip list（Lucene DocValuesSkipper），加快大型 segment 上的时间范围查询

---

## 整体效果

| 时间节点 | 吞吐量 | 磁盘存储 |
|---------|--------|---------|
| 8.15 技术预览 | ~2,500 docs/s | ~65 GB |
| 8.17 正式发布 | ~3,000 docs/s | ~45 GB |
| 9.1+ | ~3,500 docs/s | ~36 GB |

---

## 未来方向（9.4+）

**消除 `_id` 存储**（PR #144026，目前仅 TIME_SERIES，logsdb 计划跟进）：

```java
// SyntheticIdField.java（TIME_SERIES 模式）
TYPE.setStored(false);                          // 不存 stored field
TYPE.setDocValuesType(DocValuesType.BINARY);    // 以 binary doc values 保存 id 字节

// _id 由 _tsid + (Long.MAX_VALUE - @timestamp) + routing_hash 构成
// 从 doc values 中即时重构，无需 stored field
```

logsdb 模式尚未实现此优化，`_id` 仍使用 stored field。
