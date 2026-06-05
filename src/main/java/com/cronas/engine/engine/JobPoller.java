package com.cronas.engine.engine;

import com.cronas.engine.entity.Job;
import com.cronas.engine.entity.JobState;
import com.cronas.engine.repository.JobRepository;
import com.cronas.engine.service.WebhookExecutor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

//import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JobPoller {
    
    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final JobRepository jobRepository;
    private final RedissonClient redissonClient;
    private final WebhookExecutor webhookExecutor;
    
    // Explicitly using Java 21 Virtual Threads for our execution pool
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public JobPoller(JobRepository jobRepository, RedissonClient redissonClient, WebhookExecutor webhookExecutor) {
        this.jobRepository = jobRepository;
        this.redissonClient = redissonClient;
        this.webhookExecutor = webhookExecutor;
    }

    // Runs every 1000ms (1 second)
    @Scheduled(fixedDelay = 1000)
    public void pollAndExecute() {
        // Fetch up to 100 ripe jobs. 'SKIP LOCKED' prevents Postgres contention between cluster nodes.
        List<Job> ripeJobs = jobRepository.findExecutableJobsWithLock(100);

        for (Job job : ripeJobs) {
            // Immediately hand off to a Virtual Thread so the polling loop is never blocked
            virtualThreadExecutor.submit(() -> processJobSafely(job));
        }
    }

    @Transactional
    protected void processJobSafely(Job job) {
        String lockKey = "cronas:lock:" + job.getJobId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Try to acquire the Redis lock. Wait 0s, lease for 15s.
            // If Node A gets this, Node B immediately returns false and skips execution.
            if (lock.tryLock(0, 15, TimeUnit.SECONDS)) {
                
                // 1. Mark as in progress
                job.setState(JobState.IN_PROGRESS);
                jobRepository.saveAndFlush(job);

                // 2. Fire the network request
                boolean success = webhookExecutor.fireWebhook(job);

                // 3. Resolve State Engine
                if (success) {
                    job.setState(JobState.COMPLETED);
                } else {
                    handleFailure(job);
                }
                
                jobRepository.save(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // Always release the distributed lock if we currently hold it
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void handleFailure(Job job) {
        int currentRetries = job.getRetryCount();
    
    if (currentRetries >= job.getMaxRetries()) {
        // Dead Letter Queue: Max retries hit. Mark as FAILED and park it.
        job.setState(JobState.FAILED);
        log.error("Job ID: {} FAILED permanently after {} retries.", job.getJobId(), currentRetries);
    } else {
        // Exponential Backoff: Calculate delay and push back to PENDING
        job.setRetryCount(currentRetries + 1);
        job.setState(JobState.PENDING);
        
        // Delay formula: 2^retryCount minutes (1m, 2m, 4m, etc.)
        long delayMinutes = (long) Math.pow(2, currentRetries);
        job.setScheduledTime(java.time.Instant.now().plus(delayMinutes, java.time.temporal.ChronoUnit.MINUTES));
        
        log.warn("Job ID: {} failed. Backing off for {} minutes. Attempt {}/{}", job.getJobId(), delayMinutes, job.getRetryCount(), job.getMaxRetries());
    }
    }
}