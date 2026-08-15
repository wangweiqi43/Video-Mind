package com.videomind.module.agent.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkflowDecisionRunnerTest {

    @Test
    void interruptsAnInFlightDecisionWhenCancellationIsRequested() {
        WorkflowDecisionRunner runner = new WorkflowDecisionRunner();
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread requester = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        requester.start();

        assertThatThrownBy(() -> runner.run(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }, 2_000, () -> {
            if (cancelled.get()) {
                throw new WorkflowCancelledException("cancelled");
            }
        })).isInstanceOf(WorkflowCancelledException.class);

        runner.close();
    }
}
