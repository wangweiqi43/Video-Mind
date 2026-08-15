package com.videomind.module.chat.generation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatGenerationCancellationToken {
    private final Long generationId;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelHook = new AtomicReference<>();

    ChatGenerationCancellationToken(Long generationId) {
        this.generationId = generationId;
    }

    public void check() {
        if (cancellationRequested.get()) {
            throw new ChatGenerationCancelledException(generationId);
        }
    }

    public boolean cancellationRequested() {
        return cancellationRequested.get();
    }

    public void onCancel(Runnable hook) {
        cancelHook.set(hook);
        if (cancellationRequested.get()) {
            runHook(cancelHook.getAndSet(null));
        }
    }

    public void clearCancelHook() {
        cancelHook.set(null);
    }

    void cancel() {
        if (cancellationRequested.compareAndSet(false, true)) {
            runHook(cancelHook.getAndSet(null));
        }
    }

    private static void runHook(Runnable hook) {
        if (hook != null) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // Cancellation remains effective even when an optional transport hook fails.
            }
        }
    }
}
