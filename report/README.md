# OpenSearch LogsDB 优化分析

本目录记录 Elasticsearch LogsDB 技术演进的完整分析，以及 OpenSearch 3.6 的现状与实施方案。

## 文件说明

- `es-logsdb-evolution.md` — ES LogsDB 发展历程（2012–2026），技术演进参考
- `opensearch-analysis.md` — OpenSearch 3.6 现状分析，已有功能与差距清单
- `implementation-plan.md` — OpenSearch 3.6 实施方案，7 个优先级的具体代码方案

## 核心结论

### ES LogsDB 关键技术（参考）

| 技术 | ES 版本 | 存储收益 |
|------|---------|---------|
| 合成 `_source` + 索引排序 | 8.7 (TSDB) | ~-70% |
| ZSTD stored fields | 8.16 | -12%（+14% 吞吐） |
| `route_on_sort_fields` | 8.18 | -20%（额外） |
| `pattern_text` | 9.2–9.3 | message 字段 -50% |

### OpenSearch 3.6 现状

| 阶段 | 存储节省 |
|------|---------|
| 当前（启用已有功能） | **约 -50~60%** |
| 实施 P1（ZSTD）后 | 约 -62~72% |
| 实施 P1+P2+P3+P4 后 | **约 -70~77%**（≈ ES 8.18 水平） |

3.6 已具备 `index.derived_source.enabled`（合成 `_source` 等价实现），这是最大单点收益。
主要缺口：ZSTD stored fields、`index.mode: logsdb` 入口、DocValuesSkipper 写路径。
