# OpenSearch LogsDB 现状分析

> 基于 OpenSearch 3.6.1 (Lucene 10.4.0) 源码，结合 ES 9.4 实际实现对比
> 分析日期：2026-05-06

---

## 核心结论：大部分工作已经完成

OpenSearch 3.6 已经具备 ES LogsDB 最关键的功能。**不需要重建**，只需要：
1. 接通开关（`index.mode: logsdb`，让用户一个设置激活全部功能）
2. 补 3 个代码缺口（ZSTD、`_seq_no` 优化、DocValues 格式升级）
3. 可选追加：`route_on_sort_fields`

---

## Phase 0：零代码可达（配置即用）

OpenSearch 3.6 现有功能组合，**无需任何代码变更**：

```json
PUT _index_template/logs-default
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

| 技术 | 原理 | 存储收益 |
|------|------|---------|
| `derived_source.enabled` | 跳过写入 `_source` JSON 块，读时从 doc values 重构 | -30~40% |
| `index.sort`（host-first） | 相邻文档值相关，RLE 编码极高压缩比 | -15~25% |
| `match_only_text` | 去掉词频、位置信息和 norms（每字段每文档 1 字节） | -5~8% |
| `best_compression`（DEFLATE） | 比默认 LZ4 更高压缩比 | -10% |
| **合计** | | **约 -50~60%** |

这已经接近 ES 8.15 LogsDB 技术预览（-65%）的一半路程。

### Phase 0 可追加：Merge Policy 调优（动态设置，零代码）

日志索引通常有大量按时间删除旧数据的操作，降低删除文档保留比例可让 Lucene 更积极地回收空间：

```json
PUT logs-*/_settings
{
  "index.merge.policy.deletes_pct_allowed": 5,
  "index.merge.policy.segments_per_tier": 5
}
```

| 设置 | 默认值 | 推荐值 | 效果 |
|------|--------|--------|------|
| `index.merge.policy.deletes_pct_allowed` | 20% | 5% | 更积极清理删除文档，ILM 滚动后节省 5-15% 空间 |
| `index.merge.policy.segments_per_tier` | 10 | 5 | 减少 segment 碎片，提升压缩率 |

> 代价：合并 I/O 增加约 10-20%；适合冷/温层，热层建议保持默认值。

---

## 已有功能详情

### `index.derived_source.enabled`（最重要）

**OpenSearch 3.6 已完整实现**，等价于 ES `synthetic _source`。

**已支持字段类型**（源码验证，`canDeriveSourceInternal()` 通过）：
`keyword`, `integer/long/float/double/half_float/byte/short/unsigned_long`, `date`, `ip`, `boolean`, `wildcard`, `geo_point`, `text`（需 `store: true`）

**字段级附加限制**（源码：`KeywordFieldMapper:273`）：
- `keyword` 设了 `ignore_above` 或 `normalizer` → 拒绝启用 derived_source（读时无法精确还原截断/归一化的值）
- `text` 需 `store: true`（读时走 StoredFieldFetcher，无 stored 则字段从重建 _source 静默消失）

**当前限制**（相比 ES 有差距，但不影响大多数日志场景）：

| 限制 | ES 的处理方式 | OpenSearch 3.6 |
|------|-------------|---------------|
| 不支持字段（range, flat_object 等） | `_ignored_source` 存原始字节，读时合并 | 抛异常，需要 mapping 中不存在该字段 |
| nested 对象 | 部分支持 | 不支持 |
| `enabled=false` object | 支持（字段内容存 `_ignored_source`） | **与 derived_source 硬性互斥**（`ObjectMapper:1077` 直接抛出异常） |

> **重要警告**：日志中常见的 `kubernetes.labels`、`cloud.tags` 等若已设 `enabled=false`，**不能同时启用 derived_source**，否则索引创建时报错。

> **`ignore_malformed` 与 derived_source 的数据丢失风险**：两者可共存，但 malformed 值不会写入 doc values，derived_source 重建时该字段值**静默消失**，无任何报错。生产环境应保证上游数据格式严格，或不组合使用。

### 索引排序

完整支持，无差距。

### `match_only_text`

完整实现，与 ES 等价，无差距。

### `AdditionalCodecs` 接口

3.6 新增，允许插件注册自定义 codec，无需改核心代码。

### `IndexSettingProvider` 接口

**重要发现**：`MetadataCreateIndexService` 已有 `IndexSettingProvider` 扩展点，`addAdditionalIndexSettingProvider()` 已存在。这正是实现"自动识别 `logs-*-*` 数据流"的完美扩展点，**不需要新建任何框架**。

---

## 代码缺口分析

### 缺口 1：ZSTD Stored Fields（优先级最高）

**现状**：`best_compression` 走 `Lucene90StoredFieldsFormat`（DEFLATE）

**需要的代码**：
- 新建 `ZstdStoredFieldsFormat.java`（使用已有 `zstd-jni` 依赖）
- 修改 `PerFieldMappingPostingFormatCodec.storedFieldsFormat()`

**代码量**：约 150-200 行（实际逻辑约 100 行，其余是 Compressor/Decompressor 模板代码）

**存储收益**：-12%，吞吐 +14%（双赢）

**关键**：`zstd-jni:1.5.6-1` 版本号在 `gradle/libs.versions.toml:90`，artifact 依赖声明在 `libs/compress/build.gradle:24`（`com.github.luben:zstd-jni`），无新依赖。

---

### 缺口 2：`index.mode: logsdb` 入口（高优先级）

**现状**：用户需手动设置 4-5 个配置才能启用所有优化

**需要的代码**：
- `IndexMode.java`（新建，~15 行）
- `IndexSettings.INDEX_MODE` 设置（~15 行）
- `MetadataCreateIndexService` 中注入 logsdb 默认配置（~30 行）
- `LogsIndexModeSettingsProvider`（实现已有接口 `IndexSettingProvider`，~40 行）
- `PerFieldMappingPostingFormatCodec` 传入 `IndexMode`，logsdb 模式用 Lucene104 默认 dvFormat（~15 行）

**代码量**：约 **115 行**（全部基于现有扩展点，无新框架）

**效果**：用户只需 `index.mode: logsdb`，自动获得所有已有优化 + ZSTD + skip lists

---

### 缺口 3：`_seq_no` BKD → DocValuesSkipper（低成本高价值）

**现状**：`SeqNoFieldMapper` 用 `LongPoint + NumericDocValuesField`（两份数据，BKD 独立文件）

**需要的代码**：修改 `SeqNoFieldMapper.SequenceIDFields`，logsdb 模式下使用 `NumericDocValuesField.indexedField()`

**代码量**：约 **50 行**

**已有先例**：`NumberFieldMapper` 在 `skiplist=true` 时已用 `SortedNumericDocValuesField.indexedField()`（`NumberFieldMapper:644-649`），同一 Lucene 10 API 模式，确认在 3.6 中可用。

**存储收益**：`_seq_no` BKD 文件（`.dim/.dii`）消除，同时保留范围查询能力。

---

### 缺口 4：`route_on_sort_fields`（可选，有额外损耗）

**需要的代码**：修改 `IndexRouting`，当启用时按排序字段哈希而非 `_id`

**代码量**：约 **100 行**（比 ES 简单，不需要嵌入 `_id` 字节）

**存储收益**：额外 -20%（代价：1-4% 摄取损耗）

---

## 差距总表

| 功能 | 新代码量 | 存储收益 | 是否基于现有扩展点 |
|------|---------|---------|-----------------|
| `derived_source` + sort + codec 配置 | **0 行** | -50~60% | — |
| ZSTD stored fields | ~200 行 | -12%（+14%吞吐） | `zstd-jni` 已有 |
| `index.mode: logsdb` 入口 | ~115 行 | 整合所有 | `IndexSettingProvider` 已有 |
| `_seq_no` 优化 | ~50 行 | seq_no -50% | Lucene10 API 已有 |
| `route_on_sort_fields` | ~100 行 | 额外 -20% | `IndexRouting` 现有类 |
| TSDB doc values codec | ~2000+ 行 | 数值列 -60~90% | 需要新 codec 框架 |
| `pattern_text` | ~500 行 | message -50% | 需要新 FieldMapper |

**Phase 1（ZSTD + logsdb mode + seq_no）**：约 **365 行**新代码，达到 **-70~74%** 存储收益。

---

## 配置层面额外优化（不需要代码，根据实际 mapping 按需选用）

| 配置 | 场景 | 存储收益 | 与 derived_source 的关系 |
|------|------|---------|------------------------|
| 高基数字段（trace_id/request_id）设 `"doc_values": false` | 只点查不聚合的字段 | 消除该字段 .dvd/.dvm | 需同时设 `"store": true` 才能出现在重建 _source 中 |
| `"index": false` + `"doc_values": true` | 不需要全文搜索的 keyword | 消除 posting list（.tim/.tip） | 兼容，doc values 可用于 derived_source 重建 |
| `enabled=false` Object | 完全无需搜索/聚合的对象 | 消除所有子字段索引 | **硬性互斥**，不可同时启用 derived_source |
| `_source.excludes` 排除大字段 | 有 base64/raw_event 大字段 | 直接减少 _source 大小 | **静默互斥**（derived_source 启用时 excludes 完全不执行） |

---

## 不需要做的事

以下是 ES 经历了多年迭代积累的代码，**对 OpenSearch 当前没有必要复制**：

- ES 的 `_ignored_source` 机制：复杂且仅针对 ES 的历史 mapping 兼容问题，OpenSearch `derived_source` 目前的"严格模式"对日志场景足够
- ES 的 `_id` 嵌入 routing hash：过度设计，直接修改路由函数即可
- ES 的 mapping hints 检测（host.name 字段自动注入等）：增加复杂度，用户可自行配置
- ES 的 enterprise license 检查（pattern_text 等）：OpenSearch 全开源，不需要
- ES 9.x 的 `_id` 存储消除：仅 TIME_SERIES 可用，logsdb 中尚未完成，等 ES 稳定后再参考
