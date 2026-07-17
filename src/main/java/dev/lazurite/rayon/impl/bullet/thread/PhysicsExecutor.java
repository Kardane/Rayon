package dev.lazurite.rayon.impl.bullet.thread;

import dev.lazurite.rayon.impl.Rayon;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bullet 네이티브 객체에 대한 모든 접근을 하나의 플랫폼 스레드에서 직렬화한다.
 */
public final class PhysicsExecutor implements Executor, AutoCloseable {
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Thread> workerThread = new AtomicReference<>();
    private final ExecutorService delegate;

    public PhysicsExecutor() {
        this.delegate = Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform()
                .name("Rayon Physics")
                .uncaughtExceptionHandler((thread, throwable) -> recordFailure(throwable))
                .unstarted(() -> {
                    workerThread.set(Thread.currentThread());
                    runnable.run();
                }));
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (!accepting.get()) {
            throw new RejectedExecutionException("Rayon 물리 실행기가 종료 중입니다.");
        }

        delegate.execute(() -> {
            try {
                command.run();
            } catch (Throwable throwable) {
                recordFailure(throwable);
            }
        });
    }

    public <T> T call(Callable<T> command) {
        if (isOnPhysicsThread()) {
            try {
                return command.call();
            } catch (Exception exception) {
                throw new IllegalStateException("물리 작업 실행에 실패했습니다.", exception);
            }
        }

        Future<T> future = delegate.submit(() -> {
            try {
                return command.call();
            } catch (Throwable throwable) {
                recordFailure(throwable);
                throw throwable;
            }
        });

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("물리 작업 대기가 중단되었습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("물리 작업 실행에 실패했습니다.", exception);
        }
    }

    public boolean isOnPhysicsThread() {
        return Thread.currentThread() == workerThread.get();
    }

    public void throwIfFailed() {
        Throwable throwable = failure.getAndSet(null);
        if (throwable != null) {
            throw new IllegalStateException("Rayon 물리 실행기에서 예외가 발생했습니다.", throwable);
        }
    }

    private void recordFailure(Throwable throwable) {
        failure.compareAndSet(null, throwable);
        Rayon.LOGGER.error("Rayon 물리 작업이 실패했습니다.", throwable);
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }

        delegate.shutdown();
        try {
            if (!delegate.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                Rayon.LOGGER.warn("Rayon 물리 작업이 제한 시간 내 종료되지 않아 중단을 요청합니다.");
                delegate.shutdownNow();
                if (!delegate.awaitTermination(2, TimeUnit.SECONDS)) {
                    Rayon.LOGGER.error("Rayon 물리 스레드가 종료되지 않았습니다.");
                }
            }
        } catch (InterruptedException exception) {
            delegate.shutdownNow();
            Thread.currentThread().interrupt();
            Rayon.LOGGER.error("Rayon 물리 실행기 종료 대기가 중단되었습니다.", exception);
        }
    }
}
