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
        return run(decision, timeoutMillis, WorkflowCancellation.NONE);
    }

    public <T> T run(Supplier<T> decision, long timeoutMillis, WorkflowCancellation cancellation) {
        Future<T> future = executor.submit(decision::get);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (true) {
                cancellation.check();
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    throw new IllegalStateException("WORKFLOW_DECISION_TIMEOUT");
                }
                try {
                    return future.get(Math.min(TimeUnit.MILLISECONDS.toNanos(100), remainingNanos),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException pollingTimeout) {
                    // Poll cancellation until the overall decision deadline expires.
                }
            }
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
        } catch (RuntimeException cancelledOrFailed) {
            future.cancel(true);
            throw cancelledOrFailed;
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
