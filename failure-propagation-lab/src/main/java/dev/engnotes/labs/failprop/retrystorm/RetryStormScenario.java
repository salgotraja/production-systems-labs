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
package dev.engnotes.labs.failprop.retrystorm;

import dev.engnotes.labs.commons.cli.CliArgs;
import dev.engnotes.labs.failprop.cascade.RouteDemand;
import dev.engnotes.labs.failprop.cascade.ServiceConfig;
import dev.engnotes.labs.failprop.cascade.ServiceTime;

import java.util.ArrayList;
import java.util.List;

/**
 * The two retry-storm experiments over a three-service chain with two retrying edges.
 *
 * <p>Chain: {@code frontend} -&gt; {@code service-a} -&gt; {@code database}, 50 rps of client
 * demand, no client retry. Both non-leaf hops apply the same naive policy - per-call timeout
 * 400ms, retry on timeout or failure, 50ms backoff - so with R attempts per hop one client
 * request becomes up to R^2 database attempts.
 *
 * <p>The model constants are chosen for a legible, synthetic demonstration:
 * <ul>
 *   <li><strong>Upper tiers are deliberately over-provisioned</strong> (pools of 200): Post 1
 *       showed what an under-provisioned shared pool does; here nothing above the database
 *       chokes, so the full amplified load reaches the bottom - the storm in its purest
 *       form.</li>
 *   <li>The degraded database service time (500ms) deliberately exceeds the 400ms per-call
 *       timeout: a hard-down dependency where <em>no</em> attempt can succeed, so the sweep
 *       isolates the static R^2 multiplier - retries buy load, not goodput.</li>
 *   <li>The 400ms timeout is uniform across hops. A caller therefore gives up while its
 *       callee's own retry budget (~1300ms at R=3) keeps running - that mismatch is exactly
 *       what makes abandoned work pile up. Why and how to coordinate timeouts is Post 4's
 *       lesson, deliberately not solved here.</li>
 * </ul>
 *
 * <p>Experiment 1 (the sweep) measures the amplification: attempts per hop R in {1,2,3,4}
 * against a healthy and a hard-down database. Experiment 2 (the timeline) is the storm
 * itself: the database degrades transiently (1.5s to 2.5s) and recovers; with retries the
 * database attempt rate spikes toward R^2 x demand during the window and keeps decaying for
 * over a second after the trigger cleared - in-flight retry chains have inertia.
 */
public final class RetryStormScenario {

    static final int FRONTEND_WORKERS = 200;
    static final long FRONTEND_WORK_MS = 5L;
    static final int SERVICE_A_WORKERS = 200;
    static final long SERVICE_A_WORK_MS = 5L;
    static final int DB_WORKERS = 10;
    static final long DB_HEALTHY_MS = 10L;
    static final long DB_DEGRADED_MS = 500L;
    static final double DEGRADE_FROM_MS = 1_500.0;
    static final double DEGRADE_TO_MS = 2_500.0;

    static final double ROUTE_RATE_RPS = 50.0;
    static final long CLIENT_DEADLINE_MS = 1_000L;
    static final long WINDOW_MS = 100L;

    static final long PER_CALL_TIMEOUT_MS = 400L;
    static final long BACKOFF_MS = 50L;

    /** Attempts per hop swept in experiment 1; 1 = no retry, the baseline. */
    private static final int[] ATTEMPTS_SWEEP = {1, 2, 3, 4};

    private static final int TIMELINE_BASELINE_ATTEMPTS = 1;
    private static final int TIMELINE_STORM_ATTEMPTS = 3;

    /**
     * Runs both experiments for the window length in {@code args}.
     */
    public StormRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());

        List<StormSweepPoint> sweep = new ArrayList<>(ATTEMPTS_SWEEP.length * 2);
        for (String mode : new String[] {"healthy", "degraded"}) {
            long dbMs = mode.equals("healthy") ? DB_HEALTHY_MS : DB_DEGRADED_MS;
            for (int attempts : ATTEMPTS_SWEEP) {
                RetryStormSimulator.StormOutcome outcome =
                        simulator(ServiceTime.constant(dbMs), attempts).run(
                                route(), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);
                sweep.add(toSweepPoint(mode, attempts, outcome, durationMs));
            }
        }

        ServiceTime transientDb = ServiceTime.degradedBetween(
                DEGRADE_FROM_MS, DEGRADE_TO_MS, DB_HEALTHY_MS, DB_DEGRADED_MS);
        RetryStormSimulator.StormOutcome baseline =
                simulator(transientDb, TIMELINE_BASELINE_ATTEMPTS).run(
                        route(), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);
        RetryStormSimulator.StormOutcome storm =
                simulator(transientDb, TIMELINE_STORM_ATTEMPTS).run(
                        route(), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);

        return new StormRunResult(sweep, toTimeline(baseline, storm));
    }

    /** The over-provisioned chain, parameterized by the database's service-time curve. */
    static List<ServiceConfig> topology(ServiceTime dbServiceTime) {
        return List.of(
                new ServiceConfig("frontend", FRONTEND_WORKERS, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", SERVICE_A_WORKERS, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, dbServiceTime));
    }

    static RetryStormSimulator simulator(ServiceTime dbServiceTime, int attemptsPerHop) {
        return new RetryStormSimulator(
                topology(dbServiceTime),
                new RetryPolicy(attemptsPerHop, PER_CALL_TIMEOUT_MS, BACKOFF_MS));
    }

    static RouteDemand route() {
        return new RouteDemand("checkout", List.of("frontend", "service-a", "database"), ROUTE_RATE_RPS);
    }

    private static StormSweepPoint toSweepPoint(
            String mode, int attempts, RetryStormSimulator.StormOutcome outcome, long durationMs) {
        long dbAttempts = outcome.attemptsByService().get("database");
        return new StormSweepPoint(
                mode,
                attempts,
                outcome.successPct(),
                attemptsPerEligibleRoot(outcome, attempts, durationMs),
                dbAttempts / (durationMs / 1000.0),
                outcome.p99ResolutionMs());
    }

    /**
     * Amplification measured only over roots whose retry tree had room to finish spawning
     * inside the run window; later roots' trees are cut off by the window edge and would
     * understate the multiplier. The analytic budget assumes no upper-tier queueing - once a
     * pool saturates (R=4 at these constants), measured amplification genuinely drops below
     * R^2 because the saturated tier throttles the storm. That bend is signal, not noise.
     */
    private static double attemptsPerEligibleRoot(
            RetryStormSimulator.StormOutcome outcome, int attempts, long durationMs) {
        long treeBudgetMs = FRONTEND_WORK_MS + SERVICE_A_WORK_MS
                + 2L * (attempts - 1) * (PER_CALL_TIMEOUT_MS + BACKOFF_MS);
        double interMs = 1000.0 / ROUTE_RATE_RPS;
        int eligible = (int) Math.floor((durationMs - treeBudgetMs) / interMs) + 1;
        List<Integer> perRoot = outcome.leafAttemptsPerRoot();
        eligible = Math.clamp(eligible, 1, perRoot.size());

        long total = 0L;
        for (int r = 0; r < eligible; r++) {
            total += perRoot.get(r);
        }
        return (double) total / eligible;
    }

    private static List<StormWindowSample> toTimeline(
            RetryStormSimulator.StormOutcome baseline, RetryStormSimulator.StormOutcome storm) {
        List<Integer> baselineDb = baseline.windowAttemptsByService().get("database");
        List<Integer> stormDb = storm.windowAttemptsByService().get("database");
        double windowsPerSecond = 1000.0 / WINDOW_MS;

        int windows = baseline.windowSuccessPct().size();
        List<StormWindowSample> timeline = new ArrayList<>(windows);
        for (int w = 0; w < windows; w++) {
            timeline.add(new StormWindowSample(
                    (long) w * WINDOW_MS,
                    baseline.windowSuccessPct().get(w),
                    storm.windowSuccessPct().get(w),
                    baselineDb.get(w) * windowsPerSecond,
                    stormDb.get(w) * windowsPerSecond));
        }
        return timeline;
    }
}
