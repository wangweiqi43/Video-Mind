package com.videomind.module.agent.workflow;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDecisionRunner {
    private final AtomicInteger sequence = new AtomicInteger();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "workflow-decision-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public <T> T run(Supplier<T> decision, long timeoutMillis) {
        Future<T> future = executor.submit(decision::get);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException("WORKFLOW_DECISION_TIMEOUT", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WORKFLOW_DECISION_INTERRUPTED", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("WORKFLOW_DECISION_FAILED", cause);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
