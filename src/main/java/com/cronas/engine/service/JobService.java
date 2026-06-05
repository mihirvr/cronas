package com.cronas.engine.service;

import com.cronas.engine.dto.JobRequest;
import com.cronas.engine.entity.Job;
import com.cronas.engine.entity.JobState;
import com.cronas.engine.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public Job scheduleJob(JobRequest request) {
        Job job = new Job();
        job.setTargetUrl(request.getTargetUrl());
        job.setHttpMethod(request.getHttpMethod().toUpperCase());
        job.setHeaders(request.getHeaders());
        job.setPayload(request.getPayload());
        job.setScheduledTime(request.getScheduledTime());
        job.setMaxRetries(request.getMaxRetries());
        job.setState(JobState.PENDING); // Explicitly set to PENDING

        return jobRepository.save(job);
    }
}