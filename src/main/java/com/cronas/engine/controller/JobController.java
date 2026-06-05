package com.cronas.engine.controller;

import com.cronas.engine.dto.JobRequest;
import com.cronas.engine.entity.Job;
import com.cronas.engine.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<?> createJob(@Valid @RequestBody JobRequest request) {
        Job createdJob = jobService.scheduleJob(request);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of(
                        "jobId", createdJob.getJobId(),
                        "state", createdJob.getState(),
                        "scheduledTime", createdJob.getScheduledTime()
                )
        );
    }
}