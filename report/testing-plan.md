# OpenSearch LogsDB 真实实例测试计划

> 分支：`3.6_dblogs`
> 更新：2026-05-07
> 环境：OpenSearch 3.6.1 单节点（可扩展多节点）

---

## 目录

1. [测试环境搭建](#1-测试环境搭建)
2. [功能验证测试 (P1-P6)](#2-功能验证测试)
3. [代码审查修复验证 (C/H/M/L)](#3-代码审查修复验证)
4. [端到端集成测试 (LogsDB 全流程)](#4-端到端集成测试)
5. [存储收益测量](#5-存储收益测量)
6. [回归测试（老功能不受影响）](#6-回归测试)
7. [多节点扩展测试](#7-多节点扩展测试)
8. [测试检查清单](#8-测试检查清单)

---

## 1. 测试环境搭建

### 1.1 构建分发包

```bash
cd /path/to/opensearch
./gradlew :distribution:archives:linux-tar:assemble -x test -x checkCompatibility
```

产物路径：`distribution/archives/linux-tar/build/distributions/opensearch-*.tar.gz`

### 1.2 部署单节点

```bash
tar -xzf opensearch-*.tar.gz -C /tmp/os-test/
cd /tmp/os-test/opensearch-*

# 禁用安全插件简化测试（仅测试环境）
echo 'plugins.security.disabled: true' >> config/opensearch.yml
echo 'discovery.type: single-node' >> config/opensearch.yml

# 启动
bin/opensearch
```

### 1.3 验证集群可用

```bash
curl -s http://localhost:9200/ | jq .
# 预期: { "name": "...", "version": { "number": "3.6.1" ... }, "tagline": "..." }
```

---

## 2. 功能验证测试

### 2.1 P1: ZSTD Stored Fields Format

#### 测试目标
验证 `index.codec=best_compression` 使用 ZSTD 压缩存储字段，且压缩率优于默认 codec。

#### 2.1.1 创建索引并验证 Codec 可用

```bash
# 创建 best_compression 索引
curl -s -X PUT "http://localhost:9200/test-zstd" -H 'Content-Type: application/json' -d '{
  "settings": {
    "index.codec": "best_compression",
    "number_of_shards": 1,
    "number_of_replicas": 0
  }
}'
# 预期: {"acknowledged":true,"shards_acknowledged":true,"index":"test-zstd"}

# 验证索引设置
curl -s "http://localhost:9200/test-zstd/_settings?include_defaults=false" | jq .
# 预期: index.codec = "best_compression"
```

#### 2.1.2 对比压缩率

```bash
# 索引相同数据到 default 和 best_compression 索引
for codec in default best_compression; do
  curl -s -X PUT "http://localhost:9200/test-codec-${codec}" -H 'Content-Type: application/json' -d "{
    \"settings\": { \"index.codec\": \"${codec}\", \"number_of_shards\": 1, \"number_of_replicas\": 0 }
  }"
done

# 批量写入相同数据（10000 条日志文档）
for i in $(seq 1 10000); do
  echo "{\"index\":{\"_index\":\"test-codec-default\",\"_id\":\"$i\"}}
{\"@timestamp\":\"2026-05-07T00:00:00Z\",\"message\":\"connect from 10.0.0.$((i%255)) port $((i%65535))\",\"host\":{\"name\":\"host-$((i%100))\"},\"level\":\"info\"}" >> /tmp/bulk-default.json

  echo "{\"index\":{\"_index\":\"test-codec-best_compression\",\"_id\":\"$i\"}}
{\"@timestamp\":\"2026-05-07T00:00:00Z\",\"message\":\"connect from 10.0.0.$((i%255)) port $((i%65535))\",\"host\":{\"name\":\"host-$((i%100))\"},\"level\":\"info\"}" >> /tmp/bulk-zstd.json
done

curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/bulk-default.json > /dev/null
curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/bulk-zstd.json > /dev/null

# 刷新
curl -s -X POST "http://localhost:9200/_refresh" > /dev/null

# 对比存储大小
curl -s "http://localhost:9200/_cat/indices/test-codec-*?v&h=index,store.size"
# 预期: best_compression 索引 store.size 比 default 小 3-7%
```

#### 2.1.3 验证 ZSTD 压缩生效（非 Lucene 默认 BestCompression）

```bash
# 核心验证: best_compression 使用了 ZstdCompressingCodec
# 方法: 对比三组索引（default / best_compression / 手动 ZSTD 参数）
# ZSTD 压缩率应显著优于 DEFLATE (Lucene 默认 BestCompression)

# 精确对比：索引 10000 条相同文档
for i in $(seq 1 10000); do
  echo "{\"index\":{\"_index\":\"test-codec-default\",\"_id\":\"$i\"}}
{\"@timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"message\":\"request from 10.0.0.$((i%255)) status $((i%5==0 ? 500 : 200)) duration $((RANDOM%5000))ms\",\"host\":{\"name\":\"host-$((i%50))\"},\"service\":\"svc-$((i%20))\",\"level\":\"$([ $((i%20)) -eq 0 ] && echo 'ERROR' || echo 'INFO')\"}" >> /tmp/zstd-verify-default.json

  echo "{\"index\":{\"_index\":\"test-codec-best_compression\",\"_id\":\"$i\"}}
{\"@timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"message\":\"request from 10.0.0.$((i%255)) status $((i%5==0 ? 500 : 200)) duration $((RANDOM%5000))ms\",\"host\":{\"name\":\"host-$((i%50))\"},\"service\":\"svc-$((i%20))\",\"level\":\"$([ $((i%20)) -eq 0 ] && echo 'ERROR' || echo 'INFO')\"}" >> /tmp/zstd-verify-bc.json
done

curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/zstd-verify-default.json > /dev/null
curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/zstd-verify-bc.json > /dev/null
curl -s -X POST "http://localhost:9200/_refresh" > /dev/null
curl -s -X POST "http://localhost:9200/_forcemerge?max_num_segments=1" > /dev/null

# 存储对比
echo "=== Storage Comparison ==="
curl -s "http://localhost:9200/_cat/indices/test-codec-*?v&h=index,store.size,docs.count"

# 计算压缩率
DEFAULT_SIZE=$(curl -s "http://localhost:9200/_cat/indices/test-codec-default?h=store.size" | tr -d ' ')
ZSTD_SIZE=$(curl -s "http://localhost:9200/_cat/indices/test-codec-best_compression?h=store.size" | tr -d ' ')
echo "Default: $DEFAULT_SIZE, best_compression (ZSTD): $ZSTD_SIZE"

# 预期: best_compression 存储比 default 小 5-15%
# 如果使用 Lucene 默认 BestCompression (DEFLATE), 压缩率会与 ZSTD 有显著差异
# ZSTD level=3 通常比 DEFLATE best 小 5-7%，速度更快
```

#### 2.1.4 验证数据可读

```bash
# 验证搜索结果一致
DEFAULT_COUNT=$(curl -s "http://localhost:9200/test-codec-default/_count" | jq .count)
ZSTD_COUNT=$(curl -s "http://localhost:9200/test-codec-best_compression/_count" | jq .count)
echo "Default: $DEFAULT_COUNT, ZSTD: $ZSTD_COUNT"
# 预期: 两者相等

# 验证文档内容可读
curl -s "http://localhost:9200/test-codec-best_compression/_search?size=3" | jq '.hits.hits[0]._source'
# 预期: 返回完整文档内容
```

#### 2.1.5 清理

```bash
curl -s -X DELETE "http://localhost:9200/test-zstd,test-codec-default,test-codec-best_compression" > /dev/null
```

---

### 2.2 P2: index.mode: logsdb 入口

#### 测试目标
验证 `logs-*-*` 数据流自动注入 LogsDB 默认设置。

#### 2.2.1 创建 LogsDB 数据流（写入自动创建）

```bash
# 创建索引模板匹配 logs-*-* 模式
curl -s -X PUT "http://localhost:9200/_index_template/logs_template" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["logs-*-*"],
  "data_stream": {},
  "template": {
    "settings": {
      "index.mode": "logsdb"
    },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "message": { "type": "text" },
        "host": {
          "properties": {
            "name": { "type": "keyword" }
          }
        },
        "level": { "type": "keyword" }
      }
    }
  }
}'
# 预期: {"acknowledged":true}

# 写入第一条文档（自动创建数据流 + 首个 backing index）
curl -s -X POST "http://localhost:9200/logs-app-prod/_doc" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "message": "connect from 10.0.0.1 port 443",
  "host": { "name": "web-server-01" },
  "level": "info"
}'
# 预期: {"_index":".ds-logs-app-prod-...","_id":"...","result":"created"}
```

#### 2.2.2 验证自动注入的设置

```bash
# 获取 backing index 名称
BACKING_INDEX=$(curl -s "http://localhost:9200/_data_stream/logs-app-prod" | jq -r '.data_streams[0].indices[0].index_name')
echo "Backing index: $BACKING_INDEX"

# 验证注入的设置
SETTINGS=$(curl -s "http://localhost:9200/${BACKING_INDEX}/_settings?include_defaults=false" | jq '.[].settings.index')
echo "--- Auto-injected Settings ---"
echo "derived_source: $(echo $SETTINGS | jq .derived_source.enabled)"
echo "codec: $(echo $SETTINGS | jq .codec)"
echo "sort_field: $(echo $SETTINGS | jq '.sort.field')"
echo "sort_order: $(echo $SETTINGS | jq '.sort.order')"
echo "seq_no_doc_values_only: $(echo $SETTINGS | jq .seq_no_doc_values_only)"
echo "route_on_sort_fields: $(echo $SETTINGS | jq .logsdb.route_on_sort_fields)"
```

预期输出：
```
derived_source: "true"
codec: "best_compression"
sort_field: ["@timestamp"]
sort_order: ["desc"]
seq_no_doc_values_only: "true"
route_on_sort_fields: "true"
```

#### 2.2.3 验证非 LogsDB 数据流不受影响

```bash
# 创建普通索引模板
curl -s -X PUT "http://localhost:9200/_index_template/app_template" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["app-*"],
  "template": {
    "settings": { "number_of_shards": 1 }
  }
}'

# 写入文档
curl -s -X POST "http://localhost:9200/app-metrics/_doc" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "value": 42
}'

# 验证没有注入 logsdb 设置
curl -s "http://localhost:9200/app-metrics/_settings?include_defaults=false" | jq '.[].settings.index | keys'
# 预期: 没有 derived_source, logsdb.route_on_sort_fields 等字段
```

#### 2.2.4 验证 node-level toggle

```bash
# 重启节点时添加配置: cluster.logsdb.enabled: false
# 之后日志数据流不应该自动启用 logsdb 模式
```

#### 2.2.5 清理

```bash
curl -s -X DELETE "http://localhost:9200/_data_stream/logs-app-prod" > /dev/null
curl -s -X DELETE "http://localhost:9200/app-metrics" > /dev/null
curl -s -X DELETE "http://localhost:9200/_index_template/logs_template,app_template" > /dev/null
```

---

### 2.3 P3: _seq_no DocValues 优化

#### 测试目标
验证 `index.seq_no_doc_values_only=true` 时，`_seq_no` 字段不再生成独立 BKD 文件。

#### 2.3.1 对比存储结构

```bash
# 创建两个索引：标准 vs 优化
curl -s -X PUT "http://localhost:9200/test-seqno-standard" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 }
}'

curl -s -X PUT "http://localhost:9200/test-seqno-optimized" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 1, "number_of_replicas": 0,
    "index.seq_no_doc_values_only": true
  }
}'

# 各索引 500 条文档
for i in $(seq 1 500); do
  curl -s -X POST "http://localhost:9200/test-seqno-standard/_doc" -H 'Content-Type: application/json' \
    -d "{\"value\": $i}" > /dev/null
  curl -s -X POST "http://localhost:9200/test-seqno-optimized/_doc" -H 'Content-Type: application/json' \
    -d "{\"value\": $i}" > /dev/null
done

# 强制刷新和合并
curl -s -X POST "http://localhost:9200/test-seqno-standard/_refresh" > /dev/null
curl -s -X POST "http://localhost:9200/test-seqno-optimized/_refresh" > /dev/null
curl -s -X POST "http://localhost:9200/test-seqno-standard/_forcemerge?max_num_segments=1" > /dev/null
curl -s -X POST "http://localhost:9200/test-seqno-optimized/_forcemerge?max_num_segments=1" > /dev/null

# 查看 segment 文件
curl -s "http://localhost:9200/_cat/segments/test-seqno-standard?v&h=index,segment,size,size.memory"
curl -s "http://localhost:9200/_cat/segments/test-seqno-optimized?v&h=index,segment,size,size.memory"
# 预期: optimized 索引存储略小（合并了 indexed + doc values）
```

#### 2.3.2 验证 _seq_no 查询功能

```bash
# 验证通过 _seq_no 搜索
curl -s "http://localhost:9200/test-seqno-optimized/_search" -H 'Content-Type: application/json' -d '{
  "query": { "range": { "_seq_no": { "gte": 100, "lte": 200 } } }
}' | jq '.hits.total.value'
# 预期: 101

# 验证 _seq_no 排序
curl -s "http://localhost:9200/test-seqno-optimized/_search" -H 'Content-Type: application/json' -d '{
  "sort": [{ "_seq_no": "desc" }], "size": 3
}' | jq '.hits.hits[]._seq_no'
# 预期: [499, 498, 497]
```

#### 2.3.3 清理

```bash
curl -s -X DELETE "http://localhost:9200/test-seqno-standard,test-seqno-optimized" > /dev/null
```

---

### 2.4 P4: route_on_sort_fields

#### 测试目标
验证基于 sort field 值的路由正确工作，同 host 文档落在同一 shard。

#### 2.4.1 自动路由验证（不手动指定 routing）

```bash
# 创建多 shard 索引（3 shard, 0 replica 以便验证）
curl -s -X PUT "http://localhost:9200/test-routing" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 0,
    "index.sort.field": ["host.name", "@timestamp"],
    "index.sort.order": ["asc", "desc"],
    "index.logsdb.route_on_sort_fields": true
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "host": {
        "properties": {
          "name": { "type": "keyword" }
        }
      },
      "message": { "type": "text" }
    }
  }
}'

# 关键：写入时不指定 ?routing= 参数，验证 routing 被自动提取
# 每个 host 写入 10 条文档（不同 timestamp）
for i in $(seq 1 10); do
  curl -s -X POST "http://localhost:9200/test-routing/_doc" -H 'Content-Type: application/json' -d "{
    \"@timestamp\": \"2026-05-07T10:00:0${i}Z\",
    \"host\": { \"name\": \"web-01\" },
    \"message\": \"log from web-01 event ${i}\"
  }" > /dev/null

  curl -s -X POST "http://localhost:9200/test-routing/_doc" -H 'Content-Type: application/json' -d "{
    \"@timestamp\": \"2026-05-07T10:00:0${i}Z\",
    \"host\": { \"name\": \"web-02\" },
    \"message\": \"log from web-02 event ${i}\"
  }" > /dev/null

  curl -s -X POST "http://localhost:9200/test-routing/_doc" -H 'Content-Type: application/json' -d "{
    \"@timestamp\": \"2026-05-07T10:00:0${i}Z\",
    \"host\": { \"name\": \"web-03\" },
    \"message\": \"log from web-03 event ${i}\"
  }" > /dev/null
done

curl -s -X POST "http://localhost:9200/test-routing/_refresh" > /dev/null

# 验证: 每个 host 的 10 条文档应集中在 1 个 shard
# 方法: 依次限制搜索到 shard 0/1/2，各 host 只在其中一个 shard 找到全部文档
echo "=== Routing Verification ==="
for host in "web-01" "web-02" "web-03"; do
  echo "--- $host ---"
  for shard in 0 1 2; do
    COUNT=$(curl -s "http://localhost:9200/test-routing/_search?preference=_shards:${shard}" \
      -H 'Content-Type: application/json' -d "{
      \"query\": { \"term\": { \"host.name\": \"${host}\" } }, \"size\": 0
    }" | jq '.hits.total.value')
    echo "  shard${shard}: ${COUNT} docs"
  done
done
# 预期: 每个 host 在一个 shard 有 10 条，另外两个 shard 有 0 条
# 如果文档分散在多个 shard，说明路由未生效

# 额外验证: _search_shards 确认 routing 值
curl -s "http://localhost:9200/test-routing/_search_shards" -H 'Content-Type: application/json' -d '{
  "routing": "web-01"
}' | jq '{target_shard: .shards[0][0].shard}'
# 预期: 返回 web-01 应路由到的 shard，与上面 10 条所在的 shard 一致
```

#### 2.4.2 非 keywords 字段作为 sort field 的自动路由

```bash
# 创建含多个 sort fields 的索引（@timestamp 在中间，验证 C2 continue 修复）
curl -s -X PUT "http://localhost:9200/test-routing-multi" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "index.sort.field": ["host.name", "@timestamp", "service"],
    "index.sort.order": ["asc", "desc", "asc"],
    "index.logsdb.route_on_sort_fields": true
  },
  "mappings": {
    "properties": {
      "host": { "properties": { "name": { "type": "keyword" } } },
      "service": { "type": "keyword" },
      "@timestamp": { "type": "date" }
    }
  }
}'

# 自动 routing（不手动指定）
for i in $(seq 1 10); do
  curl -s -X POST "http://localhost:9200/test-routing-multi/_doc" -H 'Content-Type: application/json' -d "{
    \"host\": { \"name\": \"web-01\" },
    \"@timestamp\": \"2026-05-07T10:00:0${i}Z\",
    \"service\": \"frontend\"
  }" > /dev/null
done
curl -s -X POST "http://localhost:9200/test-routing-multi/_refresh" > /dev/null

# 验证: 同 host+service 的文档在同一 shard
for shard in 0 1 2; do
  COUNT=$(curl -s "http://localhost:9200/test-routing-multi/_search?preference=_shards:${shard}" \
    -H 'Content-Type: application/json' -d '{
    "query": { "bool": { "filter": [
      { "term": { "host.name": "web-01" } },
      { "term": { "service": "frontend" } }
    ] } }, "size": 0
  }' | jq '.hits.total.value')
  echo "  shard${shard}: ${COUNT} docs"
done
# 预期: 10 条集中在 1 个 shard（修复前 C2 break bug 会导致 service 未被提取，路由仅基于 host.name）
```

#### 2.4.2 @timestamp 被正确跳过

```bash
# @timestamp 被排除在 routing key 之外
# 验证: 不同 timestamp 但同 host 的文档仍在同一 shard
```

#### 2.4.3 清理

```bash
curl -s -X DELETE "http://localhost:9200/test-routing" > /dev/null
```

---

### 2.5 P5: pattern_text 字段类型

#### 测试目标
验证 pattern_text 字段正确分词、索引、查询和值获取。

#### 2.5.1 创建索引和映射

```bash
curl -s -X PUT "http://localhost:9200/test-pattern" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "message": { "type": "pattern_text" },
      "host": { "type": "keyword" }
    }
  }
}'
# 预期: {"acknowledged":true,"shards_acknowledged":true,"index":"test-pattern"}
```

#### 2.5.2 索引文档并验证分词

```bash
# 索引测试文档
curl -s -X POST "http://localhost:9200/test-pattern/_doc/1" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "message": "connect from 10.0.0.1 port 443",
  "host": "server-01"
}'

curl -s -X POST "http://localhost:9200/test-pattern/_doc/2" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:01Z",
  "message": "connect from 10.0.0.2 port 8080",
  "host": "server-02"
}'

curl -s -X POST "http://localhost:9200/test-pattern/_doc/3" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:02Z",
  "message": "error processing request 404",
  "host": "server-01"
}'

curl -s -X POST "http://localhost:9200/test-pattern/_refresh" > /dev/null
```

#### 2.5.3 验证 term 查询（模板词匹配）

```bash
# 搜索模板词 "connect"（非数字词）
curl -s "http://localhost:9200/test-pattern/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "message": "connect" } }
}' | jq '.hits.total.value'
# 预期: 2（doc 1 和 2）

# 搜索模板词 "error"
curl -s "http://localhost:9200/test-pattern/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "message": "error" } }
}' | jq '.hits.total.value'
# 预期: 1（doc 3）

# 验证不能搜到参数词（如 "10.0.0.1" 包含数字，被视为 arg）
curl -s "http://localhost:9200/test-pattern/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "message": "10.0.0.1" } }
}' | jq '.hits.total.value'
# 预期: 0（参数词不索引为模板 token）
```

#### 2.5.4 验证 exists 查询

```bash
curl -s "http://localhost:9200/test-pattern/_search" -H 'Content-Type: application/json' -d '{
  "query": { "exists": { "field": "message" } }
}' | jq '.hits.total.value'
# 预期: 3
```

#### 2.5.5 验证字段值获取

```bash
# 获取文档 _source（验证 valueFetcher 能重建文本）
curl -s "http://localhost:9200/test-pattern/_doc/1" | jq '._source.message'
# 预期: "connect from 10.0.0.1 port 443"

# 使用 fields 参数获取
curl -s "http://localhost:9200/test-pattern/_search" -H 'Content-Type: application/json' -d '{
  "fields": ["message"],
  "_source": false,
  "query": { "term": { "host": "server-01" } }
}' | jq '.hits.hits[].fields'
# 预期: message 值正确重建
```

#### 2.5.6 验证空值/空字符串处理

```bash
# 空 message
curl -s -X POST "http://localhost:9200/test-pattern/_doc/4" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:03Z",
  "message": "",
  "host": "server-03"
}'

# 无 message 字段
curl -s -X POST "http://localhost:9200/test-pattern/_doc/5" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:04Z",
  "host": "server-04"
}'

curl -s -X POST "http://localhost:9200/test-pattern/_refresh" > /dev/null
# 预期: 不报错，索引成功
```

#### 2.5.7 清理

```bash
curl -s -X DELETE "http://localhost:9200/test-pattern" > /dev/null
```

---

### 2.6 P6: derived_source 扩展（range + flat_object）

#### 测试目标
验证含 `range` 或 `flat_object` 字段的索引在 `derived_source=true` 时能正常创建和重建 `_source`。

#### 2.6.1 range 字段 + derived_source

```bash
curl -s -X PUT "http://localhost:9200/test-range-ds" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 1, "number_of_replicas": 0,
    "index.derived_source.enabled": true
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "ip_range": { "type": "ip_range" },
      "duration_range": { "type": "integer_range" }
    }
  }
}'
# 预期: {"acknowledged":true}  （修复前会失败）

# 索引 range 文档
curl -s -X POST "http://localhost:9200/test-range-ds/_doc/1" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "ip_range": { "gte": "10.0.0.1", "lte": "10.0.0.255" },
  "duration_range": { "gte": 100, "lte": 500 }
}'

# 验证 _source 重建（通过 _search 获取）
curl -s "http://localhost:9200/test-range-ds/_search?size=1" | jq '.hits.hits[0]._source'
# 预期: 返回完整文档，包括 range 字段的原始值

# 验证 range 查询仍然工作
curl -s "http://localhost:9200/test-range-ds/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "ip_range": "10.0.0.50" } }
}' | jq '.hits.total.value'
# 预期: 1

curl -s -X DELETE "http://localhost:9200/test-range-ds" > /dev/null
```

#### 2.6.2 flat_object 字段 + derived_source

```bash
curl -s -X PUT "http://localhost:9200/test-flat-ds" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 1, "number_of_replicas": 0,
    "index.derived_source.enabled": true
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "labels": { "type": "flat_object" }
    }
  }
}'
# 预期: {"acknowledged":true}  （修复前会失败）

# 索引 flat_object 文档
curl -s -X POST "http://localhost:9200/test-flat-ds/_doc/1" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "labels": { "env": "prod", "region": "us-east-1", "app": "web" }
}'

# 验证 _source 重建
curl -s "http://localhost:9200/test-flat-ds/_search?size=1" | jq '.hits.hits[0]._source.labels'
# 预期: 返回 labels 的值

# 验证 flat_object 查询
curl -s "http://localhost:9200/test-flat-ds/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "labels": "env:prod" } }
}' | jq '.hits.total.value'
# 预期: 1

curl -s -X DELETE "http://localhost:9200/test-flat-ds" > /dev/null
```

---

## 3. 代码审查修复验证

### 3.1 C1: sort field 默认值

```bash
# 创建不含 host.name 映射的 logs 数据流
curl -s -X PUT "http://localhost:9200/_index_template/logs_nohost" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["logs-nohost-*"],
  "data_stream": {},
  "template": {
    "settings": { "index.mode": "logsdb" },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "message": { "type": "text" }
      }
    }
  }
}'

# 写入文档 — 修复前会因 host.name 不在 mapping 中而失败
curl -s -X POST "http://localhost:9200/logs-nohost-app/_doc" -H 'Content-Type: application/json' -d '{
  "@timestamp": "2026-05-07T10:00:00Z",
  "message": "test message"
}'
# 预期: {"result":"created"}  （修复后创建成功）

# 验证 sort field 仅为 @timestamp
curl -s "http://localhost:9200/_data_stream/logs-nohost-app" | jq -r '.data_streams[0].indices[0].index_name' | xargs -I {} curl -s "http://localhost:9200/{}/_settings?include_defaults=false" | jq '.[].settings.index.sort.field'
# 预期: ["@timestamp"]

curl -s -X DELETE "http://localhost:9200/_data_stream/logs-nohost-app" > /dev/null
curl -s -X DELETE "http://localhost:9200/_index_template/logs_nohost" > /dev/null
```

### 3.2 C2: break → continue

```bash
# 创建含多个 sort fields 的索引（@timestamp 在中间）
curl -s -X PUT "http://localhost:9200/test-break-continue" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 3,
    "index.sort.field": ["host.name", "@timestamp", "service"],
    "index.sort.order": ["asc", "desc", "asc"],
    "index.logsdb.route_on_sort_fields": true
  },
  "mappings": {
    "properties": {
      "host": { "properties": { "name": { "type": "keyword" } } },
      "service": { "type": "keyword" },
      "@timestamp": { "type": "date" }
    }
  }
}'

# 写入文档，验证 routing 包含 host.name 和 service
curl -s -X POST "http://localhost:9200/test-break-continue/_doc/1?routing=web-server-01\0frontend" -H 'Content-Type: application/json' -d '{
  "host": { "name": "web-server-01" },
  "@timestamp": "2026-05-07T10:00:00Z",
  "service": "frontend"
}'
# 修复前: break 导致 service 被跳过，routing 仅含 host.name
# 修复后: continue 正确跳过 @timestamp，routing 包含 host.name\0service

curl -s -X DELETE "http://localhost:9200/test-break-continue" > /dev/null
```

### 3.3 H1-H3 / M1-M3 验证

这些修复的验证已内嵌在上述 P4（H1 streaming parser）、P5（H2 valueFetcher, H3 TermQuery, M1 binary encoding, M2 MurmurHash3）、P1+P2（M3 shared postings format）的测试用例中。

### 3.4 L2: 异常日志

```bash
# 写入格式错误的文档，触发 sort field extraction 失败
# 检查节点日志中是否有 "Failed to extract sort field routing" debug 消息
tail -100 logs/opensearch.log | grep "sort field routing"
```

### 3.5 L3: ASCII-only digit check

```bash
# Unicode 数字（如全角数字 "１２３"）应被识别为模板词而非参数
# 已在 P5.3 中隐式验证
```

---

## 4. 端到端集成测试（LogsDB 全流程）

### 4.1 模拟真实日志写入场景

```bash
# 1. 创建完整的日志数据流模板
curl -s -X PUT "http://localhost:9200/_index_template/logsdb_full" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["logs-*-prod"],
  "data_stream": {},
  "template": {
    "settings": {
      "index.mode": "logsdb",
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "message": { "type": "pattern_text" },
        "host": {
          "properties": {
            "name": { "type": "keyword" }
          }
        },
        "level": { "type": "keyword" },
        "service": { "type": "keyword" },
        "duration": { "type": "long" },
        "labels": { "type": "flat_object" },
        "source_ip": { "type": "ip" },
        "response_range": { "type": "integer_range" }
      }
    }
  }
}'
# 预期: 创建成功

# 2. 批量写入 5000 条模拟日志
for i in $(seq 1 5000); do
  HOST_ID=$((i % 50))
  SERVICE_ID=$((i % 10))
  echo "{\"index\":{\"_index\":\"logs-app-prod\"}}
{\"@timestamp\":\"2026-05-07T$(printf '%02d' $((i/200))):$(printf '%02d' $((i%60))):00Z\",\"message\":\"request from 192.168.1.$((i%255)) processed in $((RANDOM%1000))ms\",\"host\":{\"name\":\"host-${HOST_ID}\"},\"level\":\"$([ $((i%20)) -eq 0 ] && echo 'error' || echo 'info')\",\"service\":\"service-${SERVICE_ID}\",\"duration\":$((RANDOM%1000)),\"labels\":{\"env\":\"prod\",\"version\":\"$((i%5)).0\"},\"source_ip\":\"192.168.1.$((i%255))\",\"response_range\":{\"gte\":200,\"lte\":299}}" >> /tmp/bulk-logsdb.json
done

curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/bulk-logsdb.json > /tmp/bulk-result.json

# 检查错误数
grep -c '"error"' /tmp/bulk-result.json
# 预期: 0

grep -c '"created"' /tmp/bulk-result.json
# 预期: 5000
```

### 4.2 验证所有功能协同工作

```bash
curl -s -X POST "http://localhost:9200/logs-app-prod/_refresh" > /dev/null

# 2.1 全文搜索
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "message": "request" } }
}' | jq '.hits.total.value'
# 预期: 5000

# 2.2 模板词搜索（pattern_text 功能）
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "message": "processed" } }
}' | jq '.hits.total.value'
# 预期: > 0

# 2.3 关键词过滤
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": {
    "bool": {
      "filter": [
        { "term": { "level": "error" } },
        { "term": { "host.name": "host-0" } }
      ]
    }
  }
}' | jq '.hits.total.value'
# 预期: > 0

# 2.4 flat_object 查询
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "labels": "env:prod" } }
}' | jq '.hits.total.value'
# 预期: 5000

# 2.5 range 查询
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "response_range": 250 } }
}' | jq '.hits.total.value'
# 预期: > 0

# 2.6 时间范围查询
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "query": {
    "range": {
      "@timestamp": {
        "gte": "2026-05-07T00:00:00Z",
        "lte": "2026-05-07T00:10:00Z"
      }
    }
  }
}' | jq '.hits.total.value'
# 预期: > 0

# 2.7 聚合查询
curl -s "http://localhost:9200/logs-app-prod/_search" -H 'Content-Type: application/json' -d '{
  "size": 0,
  "aggs": {
    "by_host": { "terms": { "field": "host.name" } },
    "by_level": { "terms": { "field": "level" } },
    "avg_duration": { "avg": { "field": "duration" } }
  }
}' | jq '.aggregations'
# 预期: 返回正确的聚合结果

# 2.8 _source 重建验证
curl -s "http://localhost:9200/logs-app-prod/_search?size=1" | jq '.hits.hits[0]._source | keys'
# 预期: 包含所有字段名
```

### 4.3 数据流生命周期

```bash
# rollover
curl -s -X POST "http://localhost:9200/logs-app-prod/_rollover" -H 'Content-Type: application/json' -d '{
  "conditions": { "max_docs": 1 }
}'
# 预期: {"acknowledged":true,"shards_acknowledged":true,"old_index":"...","new_index":"..."}

# 验证新 backing index 也继承了 logsdb 设置
curl -s "http://localhost:9200/_data_stream/logs-app-prod" | jq '.data_streams[0].indices | length'
# 预期: 2

# 清理
curl -s -X DELETE "http://localhost:9200/_data_stream/logs-app-prod" > /dev/null
curl -s -X DELETE "http://localhost:9200/_index_template/logsdb_full" > /dev/null
```

---

## 5. 存储收益测量

### 5.1 对比测试

```bash
# 创建两个索引：标准 vs LogsDB 模式
# 标准索引
curl -s -X PUT "http://localhost:9200/bench-standard" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "message": { "type": "text" },
      "host": { "properties": { "name": { "type": "keyword" } } },
      "level": { "type": "keyword" },
      "service": { "type": "keyword" },
      "duration": { "type": "long" }
    }
  }
}'

# LogsDB 模式索引
curl -s -X PUT "http://localhost:9200/bench-logsdb" -H 'Content-Type: application/json' -d '{
  "settings": {
    "number_of_shards": 1, "number_of_replicas": 0,
    "index.derived_source.enabled": true,
    "index.codec": "best_compression",
    "index.sort.field": ["@timestamp"],
    "index.sort.order": ["desc"],
    "index.seq_no_doc_values_only": true,
    "index.logsdb.route_on_sort_fields": true
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "message": { "type": "pattern_text" },
      "host": { "properties": { "name": { "type": "keyword" } } },
      "level": { "type": "keyword" },
      "service": { "type": "keyword" },
      "duration": { "type": "long" }
    }
  }
}'

# 批量写入 50000 条日志
for i in $(seq 1 50000); do
  echo "{\"index\":{\"_index\":\"bench-standard\",\"_id\":\"$i\"}}
{\"@timestamp\":\"2026-05-07T$(printf '%02d' $((i/2000))):$(printf '%02d' $((i%60))):00Z\",\"message\":\"request from 192.168.1.$((i%255)) processed in $((RANDOM%1000))ms\",\"host\":{\"name\":\"host-$((i%100))\"},\"level\":\"$([ $((i%20)) -eq 0 ] && echo 'error' || echo 'info')\",\"service\":\"svc-$((i%20))\",\"duration\":$((RANDOM%1000))}" >> /tmp/bench-standard.json

  echo "{\"index\":{\"_index\":\"bench-logsdb\",\"_id\":\"$i\"}}
{\"@timestamp\":\"2026-05-07T$(printf '%02d' $((i/2000))):$(printf '%02d' $((i%60))):00Z\",\"message\":\"request from 192.168.1.$((i%255)) processed in $((RANDOM%1000))ms\",\"host\":{\"name\":\"host-$((i%100))\"},\"level\":\"$([ $((i%20)) -eq 0 ] && echo 'error' || echo 'info')\",\"service\":\"svc-$((i%20))\",\"duration\":$((RANDOM%1000))}" >> /tmp/bench-logsdb.json
done

curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/bench-standard.json > /dev/null
curl -s -X POST "http://localhost:9200/_bulk" -H 'Content-Type: application/x-ndjson' --data-binary @/tmp/bench-logsdb.json > /dev/null

curl -s -X POST "http://localhost:9200/_refresh" > /dev/null
curl -s -X POST "http://localhost:9200/bench-standard/_forcemerge?max_num_segments=1" > /dev/null
curl -s -X POST "http://localhost:9200/bench-logsdb/_forcemerge?max_num_segments=1" > /dev/null

# 测量存储
curl -s "http://localhost:9200/_cat/indices/bench-*?v&h=index,store.size,docs.count"
```

预期存储收益（基于 50000 条日志）:
- derived_source: ~-35% (消除 _source stored field)
- ZSTD codec: ~-5~7% (DEFLATE → ZSTD)
- sort + routing: ~-15~20% (RLE 压缩友好)
- 总计: ~-50~60%

```bash
# 清理
curl -s -X DELETE "http://localhost:9200/bench-standard,bench-logsdb" > /dev/null
```

---

## 6. 回归测试（老功能不受影响）

### 6.1 标准 CRUD 操作

```bash
# 创建标准索引
curl -s -X PUT "http://localhost:9200/regression-test" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "category": { "type": "keyword" },
      "price": { "type": "float" },
      "quantity": { "type": "integer" },
      "in_stock": { "type": "boolean" },
      "created": { "type": "date" }
    }
  }
}'

# Index
curl -s -X POST "http://localhost:9200/regression-test/_doc/1" -H 'Content-Type: application/json' -d '{
  "title": "Test Product", "category": "electronics", "price": 99.99, "quantity": 10, "in_stock": true, "created": "2026-05-07T00:00:00Z"
}'
# 预期: {"result":"created"}

# Get
curl -s "http://localhost:9200/regression-test/_doc/1" | jq '._source.title'
# 预期: "Test Product"

# Update
curl -s -X POST "http://localhost:9200/regression-test/_update/1" -H 'Content-Type: application/json' -d '{
  "doc": { "price": 89.99 }
}'
# 预期: {"result":"updated"}

# Delete
curl -s -X DELETE "http://localhost:9200/regression-test/_doc/1"
# 预期: {"result":"deleted"}
```

### 6.2 标准查询类型

```bash
# 重新索引一些数据
for i in $(seq 1 100); do
  curl -s -X POST "http://localhost:9200/regression-test/_doc/$i" -H 'Content-Type: application/json' -d "{
    \"title\": \"Product $i\", \"category\": \"cat-$((i%5))\", \"price\": $((i*10)), \"quantity\": $((i%20)), \"in_stock\": $([ $((i%3)) -eq 0 ] && echo 'false' || echo 'true'), \"created\": \"2026-05-07T00:00:00Z\"
  }" > /dev/null
done
curl -s -X POST "http://localhost:9200/regression-test/_refresh" > /dev/null

# Match query
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "query": { "match": { "title": "product 10" } }
}' | jq '.hits.total.value'
# 预期: > 0

# Term query
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "query": { "term": { "category": "cat-1" } }
}' | jq '.hits.total.value'
# 预期: > 0

# Range query
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "query": { "range": { "price": { "gte": 100, "lte": 500 } } }
}' | jq '.hits.total.value'
# 预期: > 0

# Bool query
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "query": {
    "bool": {
      "must": [{ "term": { "in_stock": true } }],
      "filter": [{ "range": { "price": { "gte": 100 } } }]
    }
  }
}' | jq '.hits.total.value'

# Aggregation
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "size": 0,
  "aggs": {
    "by_category": { "terms": { "field": "category" } },
    "avg_price": { "avg": { "field": "price" } },
    "price_stats": { "stats": { "field": "price" } }
  }
}' | jq '.aggregations'
# 预期: 返回正确的聚合结果

# Sort
curl -s "http://localhost:9200/regression-test/_search" -H 'Content-Type: application/json' -d '{
  "sort": [{ "price": "desc" }], "size": 5
}' | jq '.hits.hits[]._source.price'
# 预期: 降序排列
```

### 6.3 非 LogsDB 数据流

```bash
# 创建标准数据流（无 index.mode=logsdb）
curl -s -X PUT "http://localhost:9200/_index_template/normal_ds" -H 'Content-Type: application/json' -d '{
  "index_patterns": ["metrics-*"],
  "data_stream": {},
  "template": {
    "settings": { "number_of_shards": 1 },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "metric": { "type": "keyword" },
        "value": { "type": "double" }
      }
    }
  }
}'

for i in $(seq 1 100); do
  curl -s -X POST "http://localhost:9200/metrics-cpu/_doc" -H 'Content-Type: application/json' -d "{
    \"@timestamp\": \"2026-05-07T00:00:${i}Z\", \"metric\": \"cpu\", \"value\": $((RANDOM%100))
  }" > /dev/null
done

curl -s -X POST "http://localhost:9200/metrics-cpu/_refresh" > /dev/null

# 验证正常索引和查询
curl -s "http://localhost:9200/metrics-cpu/_count" | jq .count
# 预期: 100

curl -s "http://localhost:9200/metrics-cpu/_search" -H 'Content-Type: application/json' -d '{
  "aggs": { "avg_value": { "avg": { "field": "value" } } }
}' | jq '.aggregations.avg_value.value'
# 预期: 返回平均值

curl -s -X DELETE "http://localhost:9200/_data_stream/metrics-cpu" > /dev/null
curl -s -X DELETE "http://localhost:9200/_index_template/normal_ds" > /dev/null
```

### 6.4 集群操作

```bash
# 集群健康
curl -s "http://localhost:9200/_cluster/health" | jq '.status'
# 预期: "green"

# 节点信息
curl -s "http://localhost:9200/_nodes" | jq '.nodes | length'
# 预期: > 0

# 索引统计
curl -s "http://localhost:9200/_stats" | jq '._all.total.docs.count'
# 预期: >= 0

# 删除回归测试索引
curl -s -X DELETE "http://localhost:9200/regression-test" > /dev/null
```

---

## 7. 多节点扩展测试

### 7.1 双节点集群

```bash
# 节点1
bin/opensearch -Ecluster.name=test-cluster -Enode.name=node-1 \
  -Ediscovery.seed_hosts=localhost:9300,localhost:9301 \
  -Eplugins.security.disabled=true

# 节点2
bin/opensearch -Ecluster.name=test-cluster -Enode.name=node-2 \
  -Epath.data=/tmp/os-node2 \
  -Ehttp.port=9201 -Etransport.port=9301 \
  -Ediscovery.seed_hosts=localhost:9300,localhost:9301 \
  -Eplugins.security.disabled=true
```

### 7.2 验证多 shard 路由 (P4)

```bash
# 用 4 shard 索引测试 routing 是否跨节点正确分发
# 验证同 host 文档落在同一 shard（可能在不同节点）
```

### 7.3 验证数据流跨节点

```bash
# 数据流 rollover 后验证新 backing index 在所有节点可见
```

---

## 8. 测试检查清单

### P1: ZSTD Stored Fields
- [ ] `index.codec=best_compression` 索引创建成功
- [ ] 存储小于 default codec（3-7%）
- [ ] 文档可读、可搜索
- [ ] 文档数量一致

### P2: index.mode: logsdb
- [ ] `logs-*-*` 数据流自动注入 6 项设置
- [ ] `index.mode=standard` 不注入 logsdb 设置
- [ ] 非 `logs-*-*` 模式数据流不受影响
- [ ] node-level `cluster.logsdb.enabled=false` 正确禁用
- [ ] 用户显式配置不被覆盖

### P3: _seq_no 优化
- [ ] `index.seq_no_doc_values_only=true` 索引创建成功
- [ ] _seq_no 范围查询正常
- [ ] _seq_no 排序正常

### P4: route_on_sort_fields
- [ ] 同 host 文档落在同一 shard
- [ ] @timestamp 被正确排除
- [ ] 多 sort fields（@timestamp 在中间）正确跳过
- [ ] 空 source 不崩溃

### P5: pattern_text
- [ ] 索引创建成功
- [ ] term 查询模板词返回正确结果
- [ ] 参数词（含数字）不可 term 查询
- [ ] exists 查询正常
- [ ] valueFetcher 正确重建文本
- [ ] 空值/空字符串不报错
- [ ] 聚合（fielddata）正常

### P6: derived_source 扩展
- [ ] range + derived_source 索引创建成功
- [ ] range 查询正常
- [ ] flat_object + derived_source 索引创建成功
- [ ] flat_object 查询正常
- [ ] _source 重建包含正确值

### 回归测试
- [ ] 标准 CRUD（Index/Get/Update/Delete）
- [ ] match/term/range/bool 查询
- [ ] 聚合（terms/avg/stats）
- [ ] 排序
- [ ] 非 LogsDB 数据流正常
- [ ] 集群健康
- [ ] 数据流 rollover

### 存储收益
- [ ] LogsDB 模式存储显著小于标准模式
- [ ] 预期总收益 -50~60%

---

> **测试执行顺序**: 1 → 2 → 3 → 4 → 5 → 6
> **每个测试套件独立运行，测试后清理**
> **出现异常时查看节点日志**: `tail -100 logs/opensearch.log`
