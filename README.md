# Cronas — Distributed Webhook Scheduler

Cronas is a high-availability **Cron-as-a-Service** platform built with **Spring Boot**, **Redis**, and **PostgreSQL**.

It allows developers to schedule precision-timed HTTP callbacks (webhooks) with guaranteed **exactly-once delivery**, even when deployed in a distributed, multi-node environment.

---

# 🚀 The Problem

In a distributed system, a standard Spring Boot `@Scheduled` task becomes a liability.

If you run three instances of your service:

- All three instances poll the database simultaneously
- All three detect the same pending job
- All three execute the same webhook

This creates duplicate executions and inconsistent state.

Cronas solves this using:

- **Distributed Locking**
- **State Machine Transitions**
- **Optimistic Concurrency Control**

This guarantees that only one instance successfully claims and executes a job.

---

# 🛠️ Tech Stack

| Component | Technology | Purpose |
|---|---|---|
| **Backend** | Java 17 / Spring Boot 3 | Core application and business logic |
| **Database** | PostgreSQL | Persistent job storage and execution logs |
| **Distributed Lock** | Redis (Redisson) | Preventing race conditions across nodes |
| **HTTP Client** | Spring WebFlux (`WebClient`) | Resilient, non-blocking webhook execution |
| **Containerization** | Docker / Docker Compose | Scaling and environment orchestration |

---

# 🏗️ How It Works

## 1. Job Submission

A client submits a `POST` request containing:

- Webhook URL
- HTTP method
- JSON payload
- `scheduledTime`

---

## 2. Persistence

The job is stored in PostgreSQL with the status:

```text
PENDING
```

---

## 3. Polling Engine

A scheduler thread polls the database every second for jobs whose scheduled time has arrived.

---

## 4. Distributed Lock Acquisition

Before processing a job, an application instance must acquire a Redis lock using the Job ID.

Example:

```java
redisson.getLock("job_" + jobId)
```

### Scenario

| Instance | Result |
|---|---|
| Instance A | ✅ Acquires lock |
| Instance B | ❌ Skips job |
| Instance C | ❌ Skips job |

Only the lock owner can continue execution.

---

## 5. Execution & Retry Logic

The winning node executes the webhook request.

If the request fails with a retriable error (such as HTTP `5xx`), the system schedules a retry using **Exponential Backoff**.

Formula:

:contentReference[oaicite:0]{index=0}

---

## 6. Final State Transition

Once processing finishes:

| Condition | Final State |
|---|---|
| Successful webhook | `COMPLETED` |
| Retry limit exceeded | `FAILED` |

This creates a complete audit trail for every job.

---

# 🌟 Key Features

## ✅ Exactly-Once Execution

Guaranteed using Redis distributed locks powered by Redisson.

---

## ✅ Optimistic Concurrency Control

Uses JPA entity versioning to prevent:

- Dirty reads
- Lost updates
- Concurrent state corruption

---

## ✅ Idempotency Headers

Automatically attaches:

```http
X-Cronas-Request-ID
```

to all outgoing webhooks so downstream systems can safely deduplicate requests.

---

## ✅ Horizontal Scalability

Designed for clustered deployment environments where nodes can be:

- Added dynamically
- Removed safely
- Restarted independently

without disrupting scheduling guarantees.

---

# 📡 API Contract

## Schedule a New Webhook

### Endpoint

```http
POST /api/v1/jobs
```

### Request Body

```json
{
  "webhookUrl": "https://api.your-service.com/callback",
  "method": "POST",
  "payload": {
    "userId": "99",
    "event": "subscription_expired"
  },
  "scheduledTime": "2026-06-01T12:00:00Z"
}
```

### Example Response

```json
{
  "jobId": "8f21a9c3",
  "status": "PENDING",
  "scheduledTime": "2026-06-01T12:00:00Z"
}
```

---

# 📂 Project Structure

```text
├── src/main/java/com/cronas
│   ├── api          # Controller layer for job ingestion
│   ├── config       # Redis (Redisson) & WebClient configuration
│   ├── core         # Scheduler engine & distributed locking logic
│   ├── model        # Entities (Job, ExecutionHistory)
│   ├── repository   # PostgreSQL JPA repositories
│   └── service      # Webhook execution & retry orchestration
│
├── docker-compose.yml  # App + Redis + PostgreSQL orchestration
└── README.md
```

---

# 🚥 Getting Started

## 1. Launch Infrastructure

```bash
docker-compose up -d
```

---

## 2. Run the Application

```bash
./mvnw spring-boot:run
```

---

## 3. Test Distributed Execution

Scale the application to three instances:

```bash
docker-compose up --scale app=3
```

You can now observe the distributed lock manager ensuring that only one instance executes each scheduled job.

---

# 🧠 Core Engineering Concepts

- Distributed Systems
- Distributed Locking
- Exactly-Once Processing
- Reactive Programming
- State Machines
- Retry Orchestration
- Fault Tolerance
- Horizontal Scaling
- Concurrency Control

---

# 📈 Future Improvements

- Cron expression scheduling
- Web dashboard for job monitoring
- Kafka/RabbitMQ integration
- Priority queues
- Multi-tenant scheduling
- Prometheus + Grafana metrics
- Rate limiting and throttling
- Dead-letter queue visualization

---

# 🐳 Deployment Architecture

Cronas is designed to run in cloud-native environments using:

- Docker
- Kubernetes
- ECS
- Distributed Redis
- Managed PostgreSQL

The system remains operational even as application instances scale horizontally.

---

# 📜 License

MIT License

---

Built for reliability. Scaled for the cloud.
