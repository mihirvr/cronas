# Cronas Engine

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring_Boot-4.0.6-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white" alt="Spring Boot 4.0.6"/>
  <img src="https://img.shields.io/badge/PostgreSQL-16-4169E1?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL 16"/>
  <img src="https://img.shields.io/badge/Redis-7.2-DC382D?style=for-the-badge&logo=redis&logoColor=white" alt="Redis 7.2"/>
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker"/>
  <img src="https://img.shields.io/badge/Prometheus-Grafana-F46800?style=for-the-badge&logo=prometheus&logoColor=white" alt="Observability"/>
</p>

<p align="center">
  <strong>A distributed, horizontally-scalable webhook scheduling engine built for guaranteed exactly-once delivery.</strong><br/>
  Java 21 Virtual Threads · Redis Distributed Locking · PostgreSQL SKIP LOCKED · Exponential Backoff
</p>

---

## What Is This?

Cronas Engine is a production-grade **delayed webhook delivery service**. Clients submit a job — a target URL, HTTP method, optional payload, and a future `scheduledTime` — via a REST API. The engine persists the job to PostgreSQL, then a cluster of nodes races to execute it at the correct moment.

The hard problem is **distributed coordination**: when multiple nodes poll the same database table simultaneously, only *one* node must fire the webhook. Cronas solves this with two independent, layered contention barriers:

1. **PostgreSQL `FOR UPDATE SKIP LOCKED`** — eliminates row-level DB contention at the query layer so competing nodes never deadlock.
2. **Redisson Distributed Lock** — provides cross-node mutual exclusion at the application layer, ensuring only the node that acquires the Redis lock fires the HTTP request.

This architecture guarantees **exactly-once delivery** even under horizontal scale, without relying on any single point of coordination.

---

## Architecture & Telemetry

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          CLIENT                                         │
│   POST /api/v1/jobs  →  { targetUrl, httpMethod, payload, time }        │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │ HTTP REST
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    CRONAS CLUSTER (Docker Compose)                      │
│                                                                         │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐                │
│  │  Node :9090  │   │  Node :9091  │   │  Node :9092  │                │
│  │              │   │              │   │              │                │
│  │  JobPoller   │   │  JobPoller   │   │  JobPoller   │                │
│  │  (VThreads)  │   │  (VThreads)  │   │  (VThreads)  │                │
│  └──────┬───────┘   └──────┬───────┘   └──────┬───────┘                │
│         │  Poll every 1s   │                  │                        │
│         └──────────────────┴──────────────────┘                        │
│                          │                                              │
│              ┌───────────┴──────────┐                                  │
│              │                      │                                   │
│              ▼                      ▼                                   │
│  ┌───────────────────┐  ┌───────────────────────────┐                  │
│  │  PostgreSQL :5432 │  │   Redis :6379 (internal)  │                  │
│  │                   │  │                           │                  │
│  │  SELECT ...       │  │  SETNX cronas:lock:{id}   │                  │
│  │  FOR UPDATE       │  │  (Redisson RLock, 15s TTL)│                  │
│  │  SKIP LOCKED      │  └───────────────────────────┘                  │
│  └───────────────────┘                                                  │
│                                                                         │
│  ┌──────────────┐   ┌──────────────────┐                               │
│  │  Prometheus  │   │     Grafana       │                               │
│  │   :9095      │──▶│     :3000         │                               │
│  └──────────────┘   └──────────────────┘                               │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │    TARGET WEBHOOK      │
                    │  POST https://...      │
                    └───────────────────────┘
```

**Job State Machine:**

```
PENDING ──► IN_PROGRESS ──► COMPLETED
                │
                └──► PENDING (retry w/ exponential backoff: 2^n minutes)
                         │
                         └──► FAILED (dead letter — max retries exceeded)
```
<img width="1820" height="610" alt="image" src="https://github.com/user-attachments/assets/13cd7e6b-5a23-45e6-bde6-5e9235343d29" />

![System Telemetry & Architecture under Load]

---

## Core Technical Deep-Dive

### 1. Dual-Layer Contention Prevention

The polling loop runs on **every node in the cluster** every 1,000 ms. Without coordination, every node would attempt to execute every eligible job simultaneously — causing duplicate deliveries and race conditions.

Cronas prevents this at two independent layers:

**Layer 1 — Database (PostgreSQL `FOR UPDATE SKIP LOCKED`)**

```sql
SELECT * FROM jobs
WHERE state = 'PENDING'
AND scheduled_time <= NOW()
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` instructs PostgreSQL to atomically skip any row that another transaction is already locking. Node A and Node B polling at the same millisecond will receive *disjoint* sets of rows. This eliminates DB-level deadlocks entirely and distributes the workload across the cluster.

**Layer 2 — Application (Redisson Distributed Lock)**

Even after `SKIP LOCKED` distributes rows, network partitions or timing gaps could allow two nodes to acquire the same row in different cycles. The Redisson lock is the final guarantee:

```java
RLock lock = redissonClient.getLock("cronas:lock:" + job.getJobId());
if (lock.tryLock(0, 15, TimeUnit.SECONDS)) {
    // Only one node reaches here per job
    webhookExecutor.fireWebhook(job);
}
```

- **Wait time = 0**: A node either acquires the lock instantly or skips — it never queues.
- **Lease = 15s**: If the executing node crashes mid-flight, Redis automatically expires the lock, allowing recovery in the next poll cycle.

### 2. Java 21 Virtual Threads (Project Loom)

The `JobPoller` dispatches every job onto a **virtual thread** via `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads are lightweight, JVM-managed threads that park (rather than block) on I/O, meaning thousands of concurrent outbound HTTP requests consume negligible OS resources.

```java
private final ExecutorService virtualThreadExecutor =
    Executors.newVirtualThreadPerTaskExecutor();

@Scheduled(fixedDelay = 1000)
public void pollAndExecute() {
    List<Job> ripeJobs = jobRepository.findExecutableJobsWithLock(100);
    for (Job job : ripeJobs) {
        virtualThreadExecutor.submit(() -> processJobSafely(job));
    }
}
```

The Spring MVC layer also uses virtual threads globally via `spring.threads.virtual.enabled: true` in `application.yaml`, replacing the Tomcat thread pool with a virtual-thread-per-request model — eliminating thread starvation under burst load.

### 3. Fault Tolerance — Exponential Backoff & Dead Letter Queue

A failed webhook is not discarded. The retry state machine applies binary exponential backoff before rescheduling:

```java
long delayMinutes = (long) Math.pow(2, currentRetries); // 1m → 2m → 4m → 8m…
job.setScheduledTime(Instant.now().plus(delayMinutes, ChronoUnit.MINUTES));
job.setState(JobState.PENDING); // Re-enters the polling queue
```

Once `retryCount >= maxRetries`, the job is permanently transitioned to `FAILED` — the **Dead Letter Queue** pattern. Failed jobs remain in the database for post-mortem inspection, never silently discarded.

### 4. Database Schema & Performance Index

The migration (`V1__init_jobs_table.sql`) creates a **composite index** specifically optimised for the polling query:

```sql
CREATE INDEX idx_jobs_state_scheduled_time ON jobs (state, scheduled_time);
```

This index allows PostgreSQL to perform an **Index Scan** (not a sequential scan) on the `(state = 'PENDING' AND scheduled_time <= NOW())` predicate. At scale, this is the difference between millisecond and second-range poll latency.

---

## Dependency Rationale

| Dependency | Why It's Here |
|---|---|
| `spring-boot-starter-webmvc` | Exposes the REST API (`POST /api/v1/jobs`) using Spring MVC. Paired with virtual threads, this delivers reactive-level throughput without the complexity of WebFlux. |
| `spring-boot-starter-data-jpa` + `postgresql` | Persistent, transactional storage of job metadata and lifecycle state. JPA + HikariCP provides a battle-hardened connection pool (`max: 20, minIdle: 5`). |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Schema migrations are version-controlled and applied automatically on startup. Guarantees every node in the cluster runs against an identical schema. |
| `spring-boot-starter-validation` | Bean Validation on `JobRequest` DTOs. Enforces `@NotBlank`, `@Future` (scheduled time must be in the future), and `@Min` constraints at the HTTP layer before touching the DB. |
| `redisson:3.30.0` | The distributed lock client. Chosen over `spring-data-redis` for its first-class `RLock` abstraction, built-in TTL management, and `tryLock(wait, lease, unit)` API — exactly the semantics needed for zero-wait, bounded-lease distributed locking. |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | Exposes `/actuator/prometheus` — a Prometheus-format metrics endpoint. Micrometer automatically instruments HikariCP pool stats, JVM heap, GC, and Virtual Thread utilisation without any manual instrumentation. |

---

## Project Structure

```
cronas-engine/
├── src/main/java/com/cronas/engine/
│   ├── CronasEngineApplication.java    # Entry point; @EnableScheduling
│   ├── config/
│   │   ├── RedisConfig.java            # Redisson client bean (pool: 5–20 connections)
│   │   └── WebClientConfig.java        # RestClient.Builder bean for WebhookExecutor
│   ├── controller/
│   │   └── JobController.java          # POST /api/v1/jobs
│   ├── dto/
│   │   └── JobRequest.java             # Validated inbound request model
│   ├── entity/
│   │   ├── Job.java                    # JPA entity; @PrePersist UUID generation
│   │   └── JobState.java               # PENDING | IN_PROGRESS | COMPLETED | FAILED
│   ├── engine/
│   │   └── JobPoller.java              # @Scheduled poller; Virtual Thread dispatcher
│   ├── repository/
│   │   └── JobRepository.java          # JPA repo; native SKIP LOCKED query
│   └── service/
│       ├── JobService.java             # Schedules jobs; @Transactional persistence
│       └── WebhookExecutor.java        # HTTP dispatch via Spring 6 RestClient
│
├── src/main/resources/
│   ├── application.yaml                # Virtual threads, HikariCP, Flyway, Actuator
│   └── db/migration/
│       └── V1__init_jobs_table.sql     # Schema + composite index
│
├── docker-compose.yml                  # Full cluster: Postgres, Redis, App, Prom, Grafana
├── Dockerfile                          # Multi-stage: Maven build → JRE runtime image
└── prometheus.yml                      # dns_sd_configs for auto-discovering scaled nodes
```

---

## Infrastructure

The entire system runs as a single `docker-compose up` command.

```yaml
# Scaled cluster: docker-compose up --scale cronas-app=3
Services:
  cronas-db       → PostgreSQL 16 (port 5432)
  cronas-redis    → Redis 7.2     (host port 6380 → internal 6379)
  cronas-app      → Spring Boot nodes (ports 9090–9092 → internal 8080)
  prometheus      → Prometheus    (port 9095)
  grafana         → Grafana       (port 3000)
```

App nodes use `depends_on` with `condition: service_healthy` to guarantee they never boot before Postgres and Redis pass their healthchecks.

**Scaling the cluster:**
```bash
docker-compose up --scale cronas-app=3
```

This brings up 3 independent Java nodes, all sharing the same Postgres and Redis instances. The polling, locking, and execution logic is fully stateless — any node can handle any job.

---

## Observability Pipeline

```
Spring Boot (Micrometer) ──► Prometheus (scraper, 5s interval) ──► Grafana (dashboard)
```

Prometheus uses **Docker DNS service discovery** (`dns_sd_configs`) to automatically detect and scrape all scaled `cronas-app` container instances at `:8080/actuator/prometheus` — no manual target registration required.

**Grafana Dashboard:** Import Dashboard ID `19004` (**Micrometer JVM Statistics**) for out-of-the-box visibility into:

| Metric | What It Tells You |
|---|---|
| `hikaricp_connections_active` | Active DB connections vs. pool ceiling (max: 20) |
| `jvm_memory_used_bytes` | Heap pressure under burst load |
| `jvm_threads_live_threads` | Virtual thread count scaling dynamically with load |
| `process_uptime_seconds` | Node uptime and restart detection |
| `http_server_requests_seconds` | API latency distribution (p50, p95, p99) |

Grafana is preconfigured at `http://localhost:3000` (admin/admin).

---

## Getting Started

**Prerequisites:** Docker, Docker Compose

```bash
# 1. Clone the repository
git clone https://github.com/mihirvr/cronas.git
cd cronas

# 2. Build and launch the full stack
docker-compose up --build --scale cronas-app=3

# 3. Verify all services are healthy
docker-compose ps
```

Services will be accessible at:

| Service | URL |
|---|---|
| Cronas Engine (Node 1) | `http://localhost:9090` |
| Cronas Engine (Node 2) | `http://localhost:9091` |
| Cronas Engine (Node 3) | `http://localhost:9092` |
| Prometheus | `http://localhost:9095` |
| Grafana | `http://localhost:3000` |

---

## API Usage

### Schedule a Webhook

**`POST /api/v1/jobs`**

```bash
curl -X POST http://localhost:9090/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "targetUrl": "https://webhook.site/your-unique-endpoint",
    "httpMethod": "POST",
    "payload": "{\"event\": \"order.confirmed\", \"orderId\": \"ORD-9821\"}",
    "scheduledTime": "2025-12-01T14:30:00Z",
    "maxRetries": 3
  }'
```

**Response `201 Created`:**
```json
{
  "jobId": "a3f8c721-4b2e-4d91-b3a1-9e7f2c0d15ab",
  "state": "PENDING",
  "scheduledTime": "2025-12-01T14:30:00Z"
}
```

**Request Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `targetUrl` | `string` | ✅ | The full URL to deliver the webhook to |
| `httpMethod` | `string` | ✅ | HTTP verb: `POST`, `GET`, `PUT`, `PATCH` |
| `payload` | `string` | ❌ | JSON body as a string (serialised before storage) |
| `headers` | `string` | ❌ | JSON-encoded custom headers |
| `scheduledTime` | `ISO 8601 UTC` | ✅ | Must be a future timestamp |
| `maxRetries` | `int` | ❌ | Defaults to `3`. Minimum `1`. |

---

## Stress Testing

The distributed locking and Virtual Thread elasticity were validated under **concurrent burst load** using a PowerShell injection script that created 50 jobs all scheduled for the **same millisecond** — the worst-case scenario for the SKIP LOCKED + Redis lock contention barrier.

```powershell
# PowerShell: Fire 50 concurrent job requests for the same scheduledTime
$scheduledTime = (Get-Date).AddSeconds(10).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
$jobs = 1..50 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url, $time)
        Invoke-RestMethod -Uri $url -Method POST -ContentType "application/json" -Body (@{
            targetUrl     = "https://webhook.site/your-endpoint"
            httpMethod    = "POST"
            payload       = "{`"batch`": `"load-test`", `"id`": $using:_}"
            scheduledTime = $time
            maxRetries    = 3
        } | ConvertTo-Json)
    } -ArgumentList "http://localhost:9090/api/v1/jobs", $scheduledTime
}
$jobs | Wait-Job | Receive-Job
```

**Results:** All 50 jobs were delivered exactly once. PostgreSQL `SKIP LOCKED` distributed rows across nodes; Redisson locks prevented cross-cycle double-execution. Virtual Thread count scaled dynamically — JVM file descriptor exhaustion was not observed at this concurrency level.

---

## Database Verification

Connect directly to the PostgreSQL container to inspect job state:

```bash
# Exec into the running Postgres container
docker exec -it cronas-db psql -U postgres -d cronas

# View all jobs and their lifecycle state
SELECT job_id, target_url, state, scheduled_time, retry_count, max_retries
FROM jobs
ORDER BY created_at DESC;

# Count jobs by state
SELECT state, COUNT(*) FROM jobs GROUP BY state;

# Inspect failed jobs (dead letter)
SELECT job_id, target_url, retry_count, updated_at
FROM jobs
WHERE state = 'FAILED'
ORDER BY updated_at DESC;
```

---

## Related Project

This project is architecturally related to **[Metricix](https://metricix.mihirr.in)** — a multi-tenant telemetry event engine (Java 21, Spring Boot WebFlux, PostgreSQL, Redis, AWS EC2) that handles high-throughput event ingestion and aggregation. Cronas Engine applies the same distributed systems principles (idempotency, distributed coordination, observable infrastructure) to the scheduled delivery domain.

---

## Author

**Mihir Revaskar**

B.Tech Electronics & Communication Engineering (Advanced Communication Technology)  
Shah & Anchor Kutchhi Engineering College, Mumbai

- 🔗 LinkedIn: [linkedin.com/in/mihir-revaskar](https://www.linkedin.com/in/mihir-revaskar/)
- 🌐 Portfolio: [mihirr.in](https://mihirr.in)
- 🔭 Related Project: [Metricix — Telemetry Event Engine](https://metricix.mihirr.in)

---

<p align="center">
  Built with precision. Engineered for failure.
</p>
