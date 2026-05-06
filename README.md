# Distributed Webhook Scheduler (Cron-as-a-Service)

A resilient, distributed background job manager built with **Spring Boot**, **Redis**, and **PostgreSQL**.

This system allows users to schedule precision-timed HTTP callbacks (webhooks) with guaranteed delivery and distributed fault tolerance.

---

# 🚀 The Architecture Challenge

Standard in-memory schedulers (like Spring's default `@Scheduled`) fail in distributed environments.

If you scale your application to multiple Docker containers, every instance will attempt to execute the same task simultaneously.

This project implements **Distributed Locking** and **Optimistic Concurrency Control** to ensure that in a cluster of `N` nodes, a job is executed **exactly once**.

---

# 🛠️ Tech Stack

| Component | Technology | Purpose |
|---|---|---|
| **Backend** | Java 17 / Spring Boot 3 | Core application framework |
| **Database** | PostgreSQL | Persistent job storage, history, and state management |
| **Distributed Lock** | Redis (Redisson) | Ensuring only one node processes a specific job |
| **HTTP Client** | Spring WebFlux (`WebClient`) | Non-blocking, reactive webhook execution |
| **Containerization** | Docker / Docker Compose | Local development and horizontal scaling simulation |

---

# 🏗️ System Workflow

## 1. Ingestion
A user submits a `POST` request with:
- Webhook URL
- JSON payload
- Target execution timestamp

## 2. Persistence
The job is stored in PostgreSQL with status:

```text
PENDING
```

The `scheduled_time` column is indexed for high-performance polling.

## 3. Polling & Distributed Locking

A manager thread runs every second and:

- Fetches jobs that are due
- Attempts to acquire a Redis distributed lock

Example:

```java
redisson.getLock("job_" + id)
```

## 4. Execution

The node that successfully acquires the lock:

1. Marks the job as `IN_PROGRESS`
2. Fires the webhook request

## 5. Resiliency & Retry Logic

If the webhook fails (for example, HTTP `5xx`), the system schedules a retry using **Exponential Backoff**.

Formula:

```math
NextRetry = InitialDelay × 2^(retry_count)
```

---

# 🌟 Key Features

## ✅ Distributed Lock Management
Prevents duplicate execution across multiple application nodes.

## ✅ Idempotency Support
Each webhook includes an `X-Job-ID` header so downstream services can safely handle duplicate requests.

## ✅ Dead Letter Queue (DLQ) Logic
Jobs that exceed maximum retry attempts are moved to a `FAILED` state for manual inspection.

## ✅ Observability
Spring Actuator endpoints expose:
- Redis connection health
- Thread pool metrics
- System health status

---

# 📡 API Contract

## Schedule a Webhook

### Endpoint

```http
POST /api/v1/jobs
```

### Request Body

```json
{
  "webhookUrl": "https://api.thirdparty.com/callback",
  "method": "POST",
  "payload": {
    "orderId": "12345",
    "status": "shipped"
  },
  "scheduledTime": "2024-12-31T23:59:59Z"
}
```

### Success Response

```json
{
  "jobId": "a1b2c3d4",
  "status": "PENDING",
  "scheduledTime": "2024-12-31T23:59:59Z"
}
```

---

# 🧠 Core Concepts Used

- Distributed Systems
- Concurrency Control
- Optimistic Locking
- Reactive Programming
- Fault Tolerance
- Retry Mechanisms
- Event Scheduling
- Horizontal Scaling

---

# 🐳 Running the Project

## Clone the Repository

```bash
git clone https://github.com/yourusername/your-repo.git
cd your-repo
```

## Start Services

```bash
docker-compose up --build
```

---

# 📈 Future Improvements

- Cron expression support
- Web dashboard for monitoring jobs
- Kafka/RabbitMQ integration
- Rate limiting
- Multi-tenant scheduling
- Priority queues
- Metrics dashboard with Prometheus + Grafana

---

# 📜 License

MIT License
