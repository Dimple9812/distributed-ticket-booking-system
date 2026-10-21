package com.ticketbooking.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Starts N tasks at the same instant. Every worker thread parks on a shared start latch;
 * the latch is opened only after all threads are ready, so requests genuinely race
 * instead of trickling in. No sleeps are involved.
 */
public final class ConcurrentRunner {

    private ConcurrentRunner() {
    }

    public static <T> List<T> runAtOnce(int parallelism, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch ready = new CountDownLatch(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < parallelism; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.apply(index);
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
