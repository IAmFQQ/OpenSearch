# OpenSearch LogsDB 工作记录

> 最后更新：2026-05-07 (全部代码修改完成)
> 分支：`3.6_dblogs`
> 状态：所有 Phase 1/2/3（除 TSDB Codec）已完成并通过编译

---

## 已完成

### Phase 1: 三个关键代码缺口

#### P1: ZSTD Stored Fields Format
- **文件：**
  - `server/.../codec/ZstdStoredFieldsFormat.java` (新建, ~115行)
  - `server/.../codec/ZstdCompressingCodec.java` (新建, ~190行)
  - `server/.../codec/CodecService.java` (修改)
  - `server/.../resources/META-INF/services/org.apache.lucene.codecs.Codec` (修改)
- **说明：** 用 zstd-jni (已有依赖) 替换 best_compression 的 DEFLATE → ZSTD level=3, 240KB block, 2048 docs/block
- **关键决策：** 不能继承 Lucene104Codec（storedFieldsFormat() 是 final），改为独立 Codec 子类
- **编译：** ✅

#### P2: index.mode: logsdb 入口
- **文件：**
  - `server/.../index/IndexMode.java` (新建, ~25行)
  - `server/.../index/IndexSettings.java` (修改, +INDEX_MODE / +isLogsDb())
  - `server/.../index/LogsIndexModeSettingsProvider.java` (新建, ~85行)
  - `server/.../cluster/metadata/MetadataCreateIndexService.java` (修改, +applyIndexModeDefaults())
  - `server/.../node/Node.java` (修改, 注册 provider)
- **说明：** STANDARD/LOGSDB 枚举，logs-*-* 数据流自动检测并注入默认设置
- **编译：** ✅

#### P3: _seq_no DocValues 优化
- **文件：**
  - `server/.../mapper/SeqNoFieldMapper.java` (修改, +SEQ_NO_DOC_VALUES_ONLY / +emptySeqID(boolean))
  - `server/.../mapper/ParsedDocument.java` (修改, null-safe updateSeqID)
  - `server/.../cluster/metadata/MetadataCreateIndexService.java` (修改)
- **说明：** 用 NumericDocValuesField.indexedField() 替代 LongPoint + NumericDocValuesField，消除独立 BKD 文件
- **编译：** ✅

### Phase 2: route_on_sort_fields

#### P4: 排序字段路由
- **文件：**
  - `server/.../index/IndexSettings.java` (修改, +LOGSDB_ROUTE_ON_SORT_FIELDS / +isRouteOnSortFieldsEnabled())
  - `server/.../action/bulk/TransportBulkAction.java` (修改, +extractSortFieldRouting())
  - `server/.../cluster/metadata/MetadataCreateIndexService.java` (修改)
- **说明：** 在 coordinating node 解析 source，提取 sort field 值作为 routing key（排除 @timestamp），同 host 文档落在同一 shard，改善 RLE 压缩
- **编译：** ✅

### Phase 3: 长期优化（部分）

#### P5: pattern_text 字段类型
- **文件：**
  - `server/.../mapper/PatternTextFieldMapper.java` (新建, ~130行)
  - `server/.../mapper/PatternTextCodec.java` (新建, ~190行)
  - `server/.../indices/IndicesModule.java` (修改, 注册新类型)
- **说明：** digit 启发式分词 (token 含数字→变量, 否则→模板词)，存储 4 个 doc values 字段 (_template, _args, _template_id, 主字段 SortedSet)。OpenSearch 3.6 无 synthetic source，value fetching 走 SourceValueFetcher
- **编译：** ✅

### 构建环境修复

- `gradle/wrapper/gradle-wrapper.properties` — gradle 镜像改为腾讯云（services.gradle.org 被墙）
- `build.gradle` — checkCompatibility 任务临时注释（raw.githubusercontent.com 被墙）

---

## Code Review 修复 (2026-05-07)

### Critical (C1, C2)
- **C1:** `MetadataCreateIndexService.java:1290` — 将 sort field 从 `["host.name", "@timestamp"]` 改为 `["@timestamp"]`，避免缺少 `host.name` 字段的数据流创建索引失败
- **C2:** `TransportBulkAction.java:1087` — `break` → `continue`，修复跳过 `@timestamp` 时丢失后续 sort fields 的 bug

### High (H1, H2, H3)
- **H1:** `TransportBulkAction` — 用 streaming `XContentParser` 替换 `XContentHelper.convertToMap()`，避免每个文档在 coordinating node 上完整解析 source JSON 为 Map
- **H2:** `PatternTextFieldMapper` — 实现 `PatternTextValueFetcher`，从 BinaryDocValues 重建原始文本，不依赖 `_source`（`derived_source=true` 时 `_source` 不可用）
- **H3:** `PatternTextFieldMapper` — 为 template token 添加 `Field(INDEXED)` 索引字段，`termQuery()` 改用标准 `TermQuery` 替代 `newSlowExactQuery()`

### Medium (M1, M2, M3)
- **M1:** `PatternTextCodec` — 用 binary 编码（`DataOutputStream`，长度前缀+UTF-8字节）替换文本编码（逗号/竖线分隔符），消除特殊字符冲突
- **M2:** `PatternTextCodec` — 用 `MurmurHash3.hash128()`（h1 作为 64-bit hash）替换自定义按字节 hash
- **M3:** `PerFieldMappingPostingFormatCodec` + `ZstdCompressingCodec` — 提取共享 `resolvePostingsFormat()` 静态方法，消除 PostingsFormat 选择的代码重复

### Low (L2, L3)
- **L2:** `TransportBulkAction.extractSortFieldRouting()` — 异常时添加 `logger.debug()` 日志，便于排查
- **L3:** `PatternTextCodec.isArg()` — 用 `c >= '0' && c <= '9'` 替换 `Character.isDigit()`，仅匹配 ASCII 数字

### 构建验证
- ✅ `./gradlew :server:compileJava` — BUIILD SUCCESSFUL
- ⚠️ `./gradlew :server:test` — Gradle 9.4.0 测试框架兼容性问题（LifecycleTrackingTestEventReporter cast 异常），与代码修改无关

---

## 未完成

### Phase 3: TSDB Doc Values Codec
- **状态：** 未开始
- **估算：** 1500-2000 行
- **说明：** 需完整自定义 Lucene DocValuesFormat，实现 RLE + PFOR-delta 编码。涉及 segment 文件格式兼容性，高风险。建议 Phase 1+2 生产验证后独立设计。
- **预期收益：** 已排序数值列 -60~90%

### Phase 3: derived_source 字段覆盖扩展
- **状态：** ✅ 已完成 (2026-05-07)
- **估算：** ~100 行
- **说明：** 为 `range`、`flat_object` 字段类型添加 derived_source 支持
- **文件：**
  - `server/.../mapper/RangeFieldMapper.java` (修改, +derivedFieldGenerator())
  - `server/.../mapper/FlatObjectFieldMapper.java` (修改, +derivedFieldGenerator())
- **编译：** ✅

### 测试
- **状态：** 未开始
- **说明：** 所有新功能（ZSTD codec、index.mode、_seq_no 优化、sort-field routing、pattern_text、derived_source extension）均缺少单元测试和集成测试
- **阻塞：** Gradle 9.4.0 测试框架兼容性问题（LifecycleTrackingTestEventReporter cast 异常）

### build.gradle 恢复
- **状态：** 待网络恢复
- **说明：** checkCompatibility 任务因 raw.githubusercontent.com 被墙临时注释，网络恢复后需还原

---

## index.mode=logsdb 自动注入设置清单

| 设置 | 值 | 作用 |
|------|-----|------|
| `index.derived_source.enabled` | true | 消除 _source stored field (-35%) |
| `index.codec` | best_compression | ZSTD stored fields (-5~7%) |
| `index.sort.field` | [@timestamp] | timestamp 排序 (-15~20%) |
| `index.sort.order` | [desc] | |
| `index.seq_no_doc_values_only` | true | 合并 indexed+doc values |
| `index.logsdb.route_on_sort_fields` | true | sort-field routing（需用户添加 sort fields） |

---

## 预期存储收益

```
Phase 0 (配置):  -50~60%
Phase 1 (代码):  -70~74%
Phase 2 (路由):  -70~77%
Phase 3 (TSDB):  超 -77%（待实施）
```
