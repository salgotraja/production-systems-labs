/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.engnotes.labs.latency.queueing;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.commons.histogram.LatencyHistogram;
import dev.engnotes.labs.commons.histogram.PercentileSnapshot;
import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Simulates a bounded M/D/c queue to demonstrate queueing theory concepts for
 * the queue-saturation experiment.
 *
 * <p>Architecture — producer-consumer model:
 * <pre>
 * Producer thread (virtual) ─── inter-arrival sleep ───► BlockingQueue&lt;QueuedRequest&gt;
 *                                                         │ (capacity: queueCapacity)
 *                                                         │ offer() → reject if full
 *                                                         ▼
 *                          Worker threads (virtual) ─── take(), sleep(serviceTimeMs) ──► histogram
 * </pre>
 *
 * <p>The sojourn time (wait in queue + service time) is recorded as latency in the
 * shared {@link LatencyHistogram}. This enables Little's Law verification:
 * {@code L = λ × W}.
 */
public final class QueueSimulator {

    private static final long WORKER_POLL_TIMEOUT_MS = 10L;
    private static final long MILLIS_PER_NANOS = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long PRODUCER_JOIN_TIMEOUT_MS = 1_000L;
    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 2_000L;

    private final int queueCapacity;
    private final long serviceTimeMs;
    private final int workerCount;
    private final boolean deterministic;

    /**
     * Creates a new {@code QueueSimulator}.
     *
     * @param queueCapacity  maximum number of requests that may wait in the queue (e.g. 200);
     *                       requests arriving when the queue is full are rejected and counted
     * @param serviceTimeMs  fixed service time per request in milliseconds (e.g. 10);
     *                       determines server utilisation ρ = λ / (μ × c)
     * @param workerCount    number of parallel worker (server) virtual threads (e.g. 1 for M/D/1)
     * @param deterministic  if {@code true}, uses a discrete-event model for reproducible
     *                       output; if {@code false}, uses virtual-thread producer and worker
     *                       scheduling with wall-clock service delay
     */
    public QueueSimulator(int queueCapacity, long serviceTimeMs, int workerCount, boolean deterministic) {
        this.queueCapacity = queueCapacity;
        this.serviceTimeMs = serviceTimeMs;
        this.workerCount = workerCount;
        this.deterministic = deterministic;
    }

    /**
     * Runs the queueing simulation for the duration specified in {@code args} at the
     * given arrival rate, then returns aggregate results.
     *
     * <p>One virtual-thread producer generates requests at {@code arrivalRateRps} and
     * offers each to a bounded {@link LinkedBlockingQueue}. A pool of {@code workerCount}
     * virtual-thread workers drain the queue, sleeping for {@code serviceTimeMs} per
     * request, and record the sojourn time (queue wait +
     * service time) into the shared histogram. A reporter on the calling thread emits
     * one {@link PercentileSnapshot} per snapshot interval.
     *
     * @param args            parsed CLI arguments controlling duration and snapshot interval
     * @param arrivalRateRps  target arrival rate in requests per second (λ)
     * @return aggregate run result including snapshots, queue depth, rejections, and mean sojourn
     */
    public QueueRunResult run(CliArgs args, double arrivalRateRps) {
        if (deterministic) {
            return runDeterministic(args, arrivalRateRps);
        }

        long startMs = System.currentTimeMillis();
        long durationMs = args.duration().toMillis();
        long snapshotIntervalMs = args.snapshotInterval().toMillis();

        LatencyHistogram histogram = new LatencyHistogram();
        BlockingQueue<QueuedRequest> queue = new LinkedBlockingQueue<>(queueCapacity);

        AtomicLong completedCount = new AtomicLong(0);
        AtomicLong rejectionCount = new AtomicLong(0);
        AtomicLong sojournSum = new AtomicLong(0);
        AtomicLong queueDepthSampleSum = new AtomicLong(0);
        AtomicLong queueDepthSampleCount = new AtomicLong(0);

        long interArrivalNanos = (long) (NANOS_PER_SECOND / arrivalRateRps);
        AtomicBoolean running = new AtomicBoolean(true);

        // --- Start worker virtual threads ---
        List<Thread> workers = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofVirtual().start(() -> {
                while (running.get() || !queue.isEmpty()) {
                    try {
                        QueuedRequest req = queue.poll(WORKER_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (req == null) {
                            continue;
                        }
                        long waitMs = System.currentTimeMillis() - req.enqueueTimeMs();
                        Thread.sleep(serviceTimeMs);
                        long sojournMs = waitMs + serviceTimeMs;
                        histogram.recordLatency(sojournMs);
                        sojournSum.addAndGet(sojournMs);
                        completedCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            workers.add(worker);
        }

        // --- Producer virtual thread ---
        Thread producer = Thread.ofVirtual().start(() -> {
            long deadline = startMs + durationMs;
            while (System.currentTimeMillis() < deadline) {
                QueuedRequest req = new QueuedRequest(System.currentTimeMillis());
                if (!queue.offer(req)) {
                    rejectionCount.incrementAndGet();
                }
                try {
                    if (deterministic) {
                        Thread.sleep(
                                interArrivalNanos / MILLIS_PER_NANOS,
                                (int) (interArrivalNanos % MILLIS_PER_NANOS));
                    } else {
                        LockSupport.parkNanos(interArrivalNanos);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        // --- Reporter loop (runs on calling thread) ---
        List<PercentileSnapshot> snapshots = new ArrayList<>();
        long nextSnapshotMs = startMs + snapshotIntervalMs;
        long prevCompleted = 0;

        while (System.currentTimeMillis() - startMs < durationMs) {
            try {
                Thread.sleep(Math.max(1, nextSnapshotMs - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long nowMs = System.currentTimeMillis();
            if (nowMs >= nextSnapshotMs) {
                int depth = queue.size();
                queueDepthSampleSum.addAndGet(depth);
                queueDepthSampleCount.incrementAndGet();

                long elapsedSeconds = (nowMs - startMs) / 1000L;
                Histogram interval = histogram.intervalHistogram();
                long nowCompleted = completedCount.get();
                long intervalCompleted = nowCompleted - prevCompleted;
                double throughputRps = intervalCompleted / (snapshotIntervalMs / 1000.0);
                prevCompleted = nowCompleted;

                snapshots.add(PercentileSnapshot.from(
                        interval, startMs, elapsedSeconds,
                        throughputRps, rejectionCount.get(), nowCompleted));
                nextSnapshotMs += snapshotIntervalMs;
            }
        }

        // --- Shutdown ---
        running.set(false);
        try {
            producer.join(PRODUCER_JOIN_TIMEOUT_MS);
            for (Thread worker : workers) {
                worker.join(SHUTDOWN_JOIN_TIMEOUT_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // --- Compute summary statistics ---
        long samples = queueDepthSampleCount.get();
        double avgDepth = samples > 0 ? (double) queueDepthSampleSum.get() / samples : 0.0;
        long totalCompleted = completedCount.get();
        double actualThroughput = durationMs > 0 ? totalCompleted / (durationMs / 1000.0) : 0.0;
        double meanSojourn = totalCompleted > 0
                ? (double) sojournSum.get() / totalCompleted
                : serviceTimeMs;

        return new QueueRunResult(snapshots, avgDepth, rejectionCount.get(),
                actualThroughput, meanSojourn);
    }

    private QueueRunResult runDeterministic(CliArgs args, double arrivalRateRps) {
        long durationMs = Math.max(1L, args.duration().toMillis());
        long snapshotIntervalMs = Math.max(1L, Math.min(args.snapshotInterval().toMillis(), durationMs));
        long totalArrivals = Math.max(1L, Math.round(arrivalRateRps * durationMs / 1_000.0));
        double interArrivalMs = 1_000.0 / arrivalRateRps;
        double[] workerAvailableAtMs = new double[workerCount];
        List<Histogram> intervals = new ArrayList<>();
        int intervalCount = Math.toIntExact(Math.max(1L, durationMs / snapshotIntervalMs));
        for (int i = 0; i < intervalCount; i++) {
            intervals.add(new Histogram(60_000L, 3));
        }

        long completed = 0L;
        long rejected = 0L;
        long sojournSum = 0L;
        long queueDepthSum = 0L;

        for (long request = 0L; request < totalArrivals; request++) {
            double arrivalMs = request * interArrivalMs;
            int workerIndex = nextWorker(workerAvailableAtMs);
            double serviceStartMs = Math.max(arrivalMs, workerAvailableAtMs[workerIndex]);
            long queuedAhead = queuedAhead(workerAvailableAtMs, arrivalMs);
            if (queuedAhead >= queueCapacity) {
                rejected++;
                queueDepthSum += queueCapacity;
                continue;
            }

            double finishMs = serviceStartMs + serviceTimeMs;
            workerAvailableAtMs[workerIndex] = finishMs;
            long sojournMs = Math.max(serviceTimeMs, Math.round(finishMs - arrivalMs));
            sojournSum += sojournMs;
            queueDepthSum += queuedAhead;
            completed++;

            int intervalIndex = Math.toIntExact(Math.min(
                    Math.max(0L, (long) arrivalMs / snapshotIntervalMs),
                    intervalCount - 1L));
            intervals.get(intervalIndex).recordValue(sojournMs);
        }

        List<PercentileSnapshot> snapshots = new ArrayList<>(intervals.size());
        long cumulativeCompleted = 0L;
        double intervalSeconds = snapshotIntervalMs / 1_000.0;
        for (int i = 0; i < intervals.size(); i++) {
            Histogram interval = intervals.get(i);
            cumulativeCompleted += interval.getTotalCount();
            long elapsedSeconds = ((i + 1L) * snapshotIntervalMs) / 1_000L;
            snapshots.add(PercentileSnapshot.from(interval, 0L, elapsedSeconds,
                    interval.getTotalCount() / intervalSeconds, rejected, cumulativeCompleted));
        }

        double avgDepth = totalArrivals == 0L ? 0.0 : (double) queueDepthSum / totalArrivals;
        double actualThroughput = completed / (durationMs / 1_000.0);
        double meanSojourn = completed == 0L ? serviceTimeMs : (double) sojournSum / completed;
        return new QueueRunResult(snapshots, avgDepth, rejected, actualThroughput, meanSojourn);
    }

    private static int nextWorker(double[] workerAvailableAtMs) {
        int selected = 0;
        for (int i = 1; i < workerAvailableAtMs.length; i++) {
            if (workerAvailableAtMs[i] < workerAvailableAtMs[selected]) {
                selected = i;
            }
        }
        return selected;
    }

    private long queuedAhead(double[] workerAvailableAtMs, double arrivalMs) {
        long queued = 0L;
        for (double availableAtMs : workerAvailableAtMs) {
            if (availableAtMs > arrivalMs) {
                queued += Math.max(1L, (long) Math.ceil((availableAtMs - arrivalMs) / serviceTimeMs));
            }
        }
        return Math.max(0L, queued - workerAvailableAtMs.length);
    }

    /**
     * Carries the enqueue timestamp for a single request so workers can compute
     * queue wait time upon dequeue.
     *
     * @param enqueueTimeMs wall-clock time (epoch millis) when the request was enqueued
     */
    private record QueuedRequest(long enqueueTimeMs) {}
}
