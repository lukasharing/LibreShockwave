package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class BackgroundExternalCastPreparser implements ExternalCastPreparser {
    private final ExecutorService executor;

    BackgroundExternalCastPreparser(int threadCount) {
        int normalizedThreadCount = Math.max(1, threadCount);
        this.executor = Executors.newFixedThreadPool(normalizedThreadCount, r -> {
            Thread t = new Thread(r, "CastParser");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public ParseJob submit(byte[] data) {
        Future<DirectorFile> future = executor.submit(() -> DirectorFile.load(data));
        return new FutureParseJob(future);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class FutureParseJob implements ParseJob {
        private final Future<DirectorFile> future;

        private FutureParseJob(Future<DirectorFile> future) {
            this.future = future;
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        @Override
        public DirectorFile getIfReady() {
            if (!future.isDone()) {
                return null;
            }
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | RuntimeException e) {
                return null;
            }
        }
    }
}
