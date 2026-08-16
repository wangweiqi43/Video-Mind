package com.videomind.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class VideoAnalysisExecutorConfig {
    public static final String BRANCH_EXECUTOR = "videoAnalysisBranchExecutor";

    @Bean(name = BRANCH_EXECUTOR)
    public ThreadPoolTaskExecutor videoAnalysisBranchExecutor(
            @Value("${videomind.analysis.branch-threads:2}") int threads,
            @Value("${videomind.analysis.branch-queue-capacity:8}") int queueCapacity) {
        if (threads < 1 || queueCapacity < 0) {
            throw new IllegalArgumentException("VIDEO_ANALYSIS_EXECUTOR_CONFIG_INVALID");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("video-analysis-branch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
