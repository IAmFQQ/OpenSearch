# OpenSearch Codebase Analysis

## Overview

**OpenSearch** is an open-source, enterprise-grade search and observability suite. It is a fork of Elasticsearch 7.10, developed under the OpenSearch Foundation. Version: **3.6.1**, built on **Lucene 10.4.0**, requiring **JDK 21** minimum. Licensed under **Apache License v2.0**, written entirely in **Java**.

---

## Repository Structure

| Directory | Description |
|---|---|
| `server/` | Core of OpenSearch: distributed system framework, search and indexing functionality |
| `modules/` | Built-in modules (included by default, not uninstallable) |
| `plugins/` | Optional plugins (separate classloader, installable via plugin-cli) |
| `libs/` | Reusable libraries for server, plugins, or modules |
| `sandbox/` | Experimental libs/modules/plugins (only in snapshot builds) |
| `buildSrc/` | Custom Gradle build framework (published as `build-tools`) |
| `distribution/` | Builds tar, zip, rpm, deb, docker packages |
| `client/` | Java clients: `rest/`, `rest-high-level/`, `sniffer/`, `benchmark/`, `test/` |
| `rest-api-spec/` | REST API specification (JSON/YAML), used for client code generation and YAML REST tests |
| `qa/` | Integration tests (multi-module, multi-version, unusual configurations) |
| `test/` | Test framework and fixtures |
| `benchmarks/` | JMH microbenchmarks |
| `docs/` | Documentation source |
| `gradle/` | Shared Gradle build configuration |
| `licenses/` | Third-party dependency licenses |

---

## Key Libraries (`libs/`)

| Lib | Purpose |
|---|---|
| `opensearch-core` | Core abstractions: `ActionListener`, `StreamInput/Output`, `TransportAddress`, `CircuitBreaker` |
| `opensearch-common` | Common utilities: settings, inject (Guice fork), network, xcontent, caching |
| `opensearch-x-content` | XContent abstraction (JSON, YAML, CBOR, SMILE) |
| `opensearch-cli` | Command-line infrastructure (plugin-cli, keystore-cli) |
| `opensearch-compress` | Compression support (Zstd) |
| `opensearch-geo` | Geographic data types and operations |
| `opensearch-nio` | Non-blocking I/O library |
| `opensearch-netty4` | Netty 4-based networking utilities |
| `opensearch-plugin-classloader` | Plugin classloader implementation |
| `opensearch-secure-sm` | Security manager utilities |
| `opensearch-ssl-config` | SSL configuration utilities |
| `opensearch-telemetry` | Telemetry abstractions (metrics, tracing) |
| `opensearch-task-commons` | Common task execution utilities |
| `dissect`, `grok` | Parse utilities (used by ingest pipelines) |

---

## Key Modules (`modules/`)

| Module | Purpose |
|---|---|
| `transport-netty4` | Default TCP transport layer |
| `transport-grpc` | gRPC transport support |
| `analysis-common` | Common text analysis components |
| `geo` | Geo utilities and field mappers |
| `ingest-common` | Common ingest processors (grok, dissect, date, etc.) |
| `ingest-geoip` | GeoIP ingest processor (MaxMind) |
| `ingest-user-agent` | User-agent parsing |
| `lang-painless` | Painless scripting language (primary scripting) |
| `lang-expression` | Expression-based scripting |
| `lang-mustache` | Mustache templating |
| `mapper-extras` | Extra field types (token count, etc.) |
| `parent-join` | Join field type (parent/child relationships) |
| `percolator` | Percolator (stored queries) |
| `reindex` | Reindex, update-by-query, delete-by-query APIs |
| `rank-eval` | Ranking evaluation APIs |
| `search-pipeline-common` | Search pipeline processors |
| `repository-url` | URL-based snapshot repository |
| `aggs-matrix-stats` | Matrix stats aggregation |
| `cache-common` | Common caching infrastructure |
| `autotagging-commons` | Auto-tagging utilities |

---

## Key Plugins (`plugins/`)

| Plugin | Purpose |
|---|---|
| `discovery-ec2`, `discovery-azure-classic`, `discovery-gce` | Cloud-based discovery |
| `repository-s3`, `repository-azure`, `repository-gcs`, `repository-hdfs` | Snapshot repositories |
| `analysis-icu`, `analysis-kuromoji`, `analysis-nori`, etc. | Language-specific analysis |
| `ingest-attachment` | Attachment ingest (Apache Tika) |
| `ingestion-fs`, `ingestion-kafka`, `ingestion-kinesis` | Streaming ingestion sources |
| `mapper-annotated-text`, `mapper-murmur3`, `mapper-size` | Additional field mappers |
| `transport-reactor-netty4` | Reactor Netty transport |
| `telemetry-otel` | OpenTelemetry integration |
| `arrow-flight-rpc` | Arrow Flight RPC |
| `workload-management` | Workload management |
| `crypto-kms` | KMS-based encryption |

---

## Architecture Overview

### Node Architecture

The `Node` class (`server/.../node/Node.java`, ~2467 lines) is the central orchestrator:

1. Reads settings and environment
2. Creates the **Guice Injector** by installing all modules
3. Initializes plugins
4. Creates core services: `ClusterService`, `TransportService`, search/indices services
5. Manages node lifecycle via `AbstractLifecycleComponent` (init -> start -> stop -> close)

### Dependency Injection

Uses a **fork of Google Guice** (in `common/inject/`). Key DI modules loaded by `Node`:

| Module | Responsibility |
|---|---|
| `ActionModule` | Registers all REST actions and transport actions |
| `ClusterModule` | Registers cluster services, allocation deciders, metadata types |
| `SearchModule` | Registers query types, aggregations, suggesters, sort types |
| `IndexModule` | Configures engine, mapper, similarity, analysis |
| `NetworkModule` | Registers transport and HTTP implementations |
| `SettingsModule` | Registers settings |
| `CacheModule` | Configures caching infrastructure |

### Plugin System Architecture

Plugins extend the abstract `Plugin` class and optionally implement SPI interfaces:

| SPI Interface | Extension Points |
|---|---|
| `ActionPlugin` | REST handlers, transport actions |
| `SearchPlugin` | Queries, aggregations, suggesters, highlights, sort, rescore |
| `AnalysisPlugin` | Analyzers, tokenizers, char/token filters |
| `MapperPlugin` | Field mappers, metadata field mappers |
| `IngestPlugin` | Ingest processors |
| `NetworkPlugin` | Transport/HTTP implementations |
| `DiscoveryPlugin` | Discovery implementations |
| `RepositoryPlugin` | Snapshot repository implementations |
| `ScriptPlugin` | Script engines, script contexts |
| `ClusterPlugin` | Allocation deciders, custom metadata/state |
| `EnginePlugin` | Custom engine implementations |
| `CachePlugin` | Custom caches |
| `CircuitBreakerPlugin` | Custom circuit breakers |
| `SystemIndexPlugin` | System index management |
| `PersistentTaskPlugin` | Persistent task executors |
| `TelemetryPlugin` | Telemetry (metrics/tracing) |
| `IdentityPlugin` | Identity/authentication support |

**Plugin Loading**: `PluginsService` scans `plugins/` directory and classpath. Each plugin gets its own child classloader (`plugin-classloader` lib) for isolation.

### Cluster Architecture

```
ClusterService
├── ClusterManagerService  — handles cluster state updates (runs only on cluster manager)
└── ClusterApplierService  — applies cluster state changes on all nodes
```

- **`Coordinator`** — Implements consensus-based leader election (~1940 lines)
- **`AllocationService`** — Manages shard routing decisions
- **`ShardsAllocator`** — Pluggable shard allocation (`BalancedShardsAllocator`)
- **`AllocationDecider`** — Chain of deciders (disk, awareness, filters, etc.)

### Cluster Coordination

1. **Election**: `PreVoteCollector` + `Coordinator` — node with highest cluster state term wins
2. **Joining**: Nodes send `JoinRequest` to elected cluster manager via `JoinHelper`
3. **Publishing**: Cluster manager publishes via `PublicationTransportHandler` -> `Publication` -> `FollowersChecker`
4. **Cluster State**: `ClusterState` (immutable) contains metadata, routing table, nodes, blocks; versioned with monotonically increasing terms

### Transport Layer

- **`TransportService`** — Central transport service (~1859 lines)
- **`TcpTransport`** — Base TCP transport implementation
- **`Netty4Transport`** — Default Netty 4-based implementation
- **Connection management**: `ConnectionManager`, `ClusterConnectionManager`, `RemoteConnectionManager`
- **Remote clusters**: `RemoteClusterService`, `RemoteClusterConnection` for cross-cluster search
- **gRPC**: `modules/transport-grpc`

### Search Pipeline

```
RestSearchAction -> TransportSearchAction (coordinator)
  ├── Phase 1: CanMatch — pre-filter shards that cannot match
  ├── Phase 2: DFS — distributed frequency statistics (if needed)
  ├── Phase 3: Query — executes query on each shard
  ├── Phase 4: Fetch — retrieves documents from matching shards
  ├── Phase 5: Expand — for collapse/suggest
  └── Scroll/PIT — stateful search contexts
```

Per-shard: `SearchService` executes via Lucene `IndexSearcher`. `SearchBackpressureService` monitors/cancels slow searches. Concurrent Segment Search available via `ConcurrentSearchRequestDecider`.

### Indexing Pipeline

```
RestIndexAction -> TransportIndexAction (routed to primary shard)
  ├── Ingest Pipeline (optional): IngestService executes processors
  ├── Engine: InternalEngine writes to Lucene + Translog
  ├── Replication: PrimaryReplicaSyncer syncs to replicas
  ├── Refresh: makes data searchable
  ├── Flush: commits to Lucene, truncates translog
  └── Merge: background merging (MergePolicy)
```

---

## Build System

- **Gradle Wrapper** (`gradlew`/`gradlew.bat`)
- **Version catalog**: `gradle/libs.versions.toml` for centralized dependency management
- **Root name**: `OpenSearch`, **group**: `org.opensearch`, **version**: `3.6.1`

### Custom Gradle Plugins (in `buildSrc/`)

| Plugin | Purpose |
|---|---|
| `opensearch.build` | Standard build conventions (applied to all subprojects) |
| `opensearch.publish` | Maven publishing configuration |
| `opensearch.internal-cluster-test` | Integration test support |
| `opensearch.optional-dependencies` | Optional dependency handling |
| `opensearch.docker-support` | Docker build support |
| `opensearch.global-build-info` | Global build metadata |

### Key Dependencies

| Dependency | Version | Usage |
|---|---|---|
| Apache Lucene | 10.4.0 | Core search/indexing |
| Netty | 4.2.13.Final | Network transport |
| Protobuf | 3.25.8 | Protocol Buffers |
| Project Reactor | 3.8.5 | Reactive streams |
| gRPC | 1.75.0 | gRPC transport |
| Log4j | 2.25.3 | Logging |
| Jackson | 2.21.2 | JSON parsing |
| JNA | 5.16.0 | Native OS access |
| AWS SDK v2 | 2.32.29 | S3 repository |
| OpenTelemetry | 1.60.1 | Telemetry |

### Build Commands

```bash
./gradlew assemble                     # Build all distributions
./gradlew localDistro                  # Build for local platform only
./gradlew run                          # Run from source
./gradlew check                        # All verification tasks
./gradlew precommit                    # Precommit checks only
./gradlew test                         # Unit tests
./gradlew internalClusterTest          # In-memory cluster tests
./gradlew generateProto                # Regenerate protobuf code
./gradlew spotlessApply                # Apply Java formatting
./gradlew japicmp                      # Check API compatibility
```

---

## Testing Strategy

### Test Types

| Type | Location | Base Class | Command |
|---|---|---|---|
| Unit | `src/test/` | `OpenSearchTestCase` | `./gradlew test` |
| Internal Cluster | `src/internalClusterTest/` | `OpenSearchSingleNodeTestCase` / `OpenSearchIntegTestCase` | `./gradlew internalClusterTest` |
| Java REST | per module | `OpenSearchRestTestCase` | `./gradlew javaRestTest` |
| YAML REST | `rest-api-spec/` | `ClientYamlTestSuiteIT` | `./gradlew yamlRestTest` |

### Naming Conventions

- Unit tests: **`*Tests.java`**
- Integration tests: **`*IT.java`**

### Best Practices

- Prefer unit tests over multi-threaded integration tests
- Use randomization for non-behavior-affecting parameters
- Never use `Thread.sleep` — use `assertBusy` or `CountDownLatch`
- Don't depend on specific segment topology
- Use `-Dtests.seed=DEADBEEF` for reproducibility
- Use `-Dtests.iters=N` for stability verification
- Feature flags: `@LockFeatureFlag` + `FeatureFlags.TestUtils`

---

## Key Source Code Patterns

### Package Structure (`server/src/main/java/org/opensearch/`)

```
action/          — Transport actions + REST actions
  admin/         — Admin operations (cluster, indices)
  search/        — Search actions (search, scroll, PIT, multi-search)
  bulk/          — Bulk indexing
  ingest/        — Ingest actions
  support/       — Action infrastructure (replication, broadcast)
bootstrap/       — Node startup, security setup
cluster/         — Cluster state, coordination, metadata, routing
  coordination/  — Leader election, cluster state publishing
  metadata/      — Index metadata, mapping, templates
  routing/       — Shard routing tables
  service/       — ClusterService, ClusterManagerService
common/          — Shared utilities, Guice inject fork, settings
discovery/       — Node discovery, PeerFinder
extensions/      — Proto-based extensibility framework
gateway/         — Persisted cluster state, shard recovery
http/            — HTTP server transport interfaces
index/           — Index-level components
  engine/        — InternalEngine, NRTReplicationEngine
  mapper/        — FieldMapper, MapperService
  shard/         — IndexShard, recovery, replication
  codec/         — Lucene codec integration
  translog/      — Transaction log
  store/         — Directory store
  cache/         — Query cache
indices/         — Indices-level management (IndicesService)
ingest/          — Ingest pipeline framework
node/            — Node.java, NodeService
plugins/         — Plugin API interfaces
rest/            — REST layer (RestHandler, RestController)
  action/        — REST action handlers
script/          — Scripting framework
search/          — Search functionality
  aggregations/  — Aggregation framework
  query/         — Query execution
  suggest/       — Suggestion framework
  pipeline/      — Search pipeline
snapshots/       — Snapshot/restore service
telemetry/       — Metrics and tracing
transport/       — Transport layer abstraction
wlm/             — Workload management
```

### Annotations

| Annotation | Meaning |
|---|---|
| `@PublicApi(since = "1.0.0")` | Backwards compatibility guaranteed |
| `@InternalApi` | No backwards compatibility guarantees |
| `@ExperimentalApi` | May change at any time |
| `@DeprecatedApi` | Deprecated API |

### Architectural Patterns

| Pattern | Description |
|---|---|
| **Dependency Injection** | Guice fork for wiring components |
| **Lifecycle** | `AbstractLifecycleComponent` (init -> start -> stop -> close) |
| **Action/Request/Response** | `ActionType` -> `Request` -> `TransportAction` -> `Response` |
| **Stream Serialization** | `StreamInput`/`StreamOutput` for binary wire format |
| **XContent Parsing** | JSON/YAML/CBOR/SMILE via `XContentParser`/`XContentBuilder` |
| **NamedWriteable Registry** | Named serializable types for wire protocol |
| **Settings Framework** | Declarative `Setting` objects with validation and dynamic updates |
| **Plugin SPI Pattern** | Abstract base class + optional SPI interfaces |
| **Module Pattern** | Guice `Module` classes registering components |
| **Cluster State Machine** | Immutable, versioned `ClusterState` published via consensus |
| **Distributed Action Pattern** | Coordinating node -> routing -> shard execution -> response merge |

---

## Modules vs Plugins vs Sandbox

| Aspect | Modules | Plugins | Sandbox |
|---|---|---|---|
| Location | `modules/` | `plugins/` | `sandbox/` |
| Installable | No (built-in) | Yes (plugin-cli) | No (snapshot only) |
| Classloader | Server classloader | Isolated classloader | Server classloader |
| Availability | Always | User-installed | Snapshot builds only |

---

## Performance & Operations Features

- **Thread pools**: `ThreadPool` with configurable executors for different workloads
- **Circuit breakers**: `CircuitBreaker` hierarchy (parent, request, fielddata, etc.) to prevent OOM
- **Rate limiting**: `RateLimiting` / admission control in `ratelimitting/` package
- **Workload management**: `wlm/` package for query/workload governance
- **Search backpressure**: `SearchBackpressureService` for slow/cancelled search management
- **Caching**: Query cache (`index/cache/`), request cache, pluggable via `CachePlugin`
- **Concurrent segment search**: Search multiple Lucene segments in parallel
