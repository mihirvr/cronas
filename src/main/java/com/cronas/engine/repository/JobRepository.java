package com.cronas.engine.repository;

import com.cronas.engine.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    /**
     * Highly optimized polling query.
     * Selects PENDING jobs whose scheduled time has arrived or passed.
     * FOR UPDATE SKIP LOCKED ensures that if Node A is currently locking a row,
     * Node B will instantly skip it and grab the next available rows, preventing DB deadlocks.
     */
    @Query(value = """
            SELECT * FROM jobs 
            WHERE state = 'PENDING' 
            AND scheduled_time <= NOW() 
            LIMIT :batchSize 
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> findExecutableJobsWithLock(@Param("batchSize") int batchSize);
}