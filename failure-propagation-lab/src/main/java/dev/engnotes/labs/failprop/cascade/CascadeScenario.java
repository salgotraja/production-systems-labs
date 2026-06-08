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
package dev.engnotes.labs.failprop.cascade;

import dev.engnotes.labs.commons.cli.CliArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * The two cascading-failures experiments over a fixed four-service topology.
 *
 * <p>Topology: {@code frontend} (pool 20) fans client demand into two routes -
 * route a = frontend -&gt; service-a (pool 10) -&gt; database (pool 10), and
 * route b = frontend -&gt; service-b (pool 10). Route b never touches the database; the only
 * thing it shares with route a is the frontend worker pool. That shared pool is the blast
 * radius this experiment measures.
 *
 * <p>The model constants are chosen for a legible, synthetic demonstration:
 * <ul>
 *   <li>{@code DB_WORKERS = 10}: the database sustains {@code 10 / serviceTime} rps, so route
 *       a's 50 rps demand crosses capacity exactly at a 200ms database service time - the
 *       sweep's cliff edge sits on round arithmetic.</li>
 *   <li>{@code FRONTEND_WORKERS = 20} is 13x the healthy steady-state need (~1.5 workers), so
 *       the cascade that exhausts it is unambiguously caused by the degraded dependency, not
 *       by tight provisioning.</li>
 *   <li>{@code CLIENT_DEADLINE_MS = 1000} is 50x the healthy route-a latency (~20ms), so a
 *       failure means the system got dramatically slower, not marginally.</li>
 *   <li>No timeouts, no retries: the purest form of the cascade. Both knobs are later posts'
 *       subjects (retries Post 2, timeout budgets Post 4).</li>
 * </ul>
 *
 * <p>Experiment 1 (the sweep) holds the database service time constant per point and sweeps it
 * across the capacity edge. Experiment 2 (the timeline) degrades the database mid-run
 * (10ms -&gt; 500ms at t=2s) and samples per-window route success plus per-service queue depth,
 * which shows the backlog stacking <em>upstream</em> of the bottleneck.
 */
public final class CascadeScenario {

    static final int FRONTEND_WORKERS = 20;
    static final long FRONTEND_WORK_MS = 5L;
    static final int SERVICE_A_WORKERS = 10;
    static final long SERVICE_A_WORK_MS = 5L;
    static final int SERVICE_B_WORKERS = 10;
    static final long SERVICE_B_WORK_MS = 10L;
    static final int DB_WORKERS = 10;
    static final long DB_HEALTHY_MS = 10L;
    static final long DB_DEGRADED_MS = 500L;
    static final double DEGRADE_AT_MS = 2_000.0;

    static final double ROUTE_RATE_RPS = 50.0;
    static final long CLIENT_DEADLINE_MS = 1_000L;
    static final long WINDOW_MS = 100L;

    /** Database service times swept across the 200ms capacity edge (10 workers vs 50 rps). */
    private static final long[] DB_SERVICE_SWEEP_MS = {10, 50, 100, 150, 200, 250, 300, 400, 500};

    /**
     * Runs both experiments for the window length in {@code args}.
     */
    public CascadeRunResult run(CliArgs args) {
        long durationMs = Math.max(1L, args.duration().toMillis());

        List<CascadeSweepPoint> sweep = new ArrayList<>(DB_SERVICE_SWEEP_MS.length * 2);
        for (long dbServiceMs : DB_SERVICE_SWEEP_MS) {
            CascadeSimulator simulator = new CascadeSimulator(topology(ServiceTime.constant(dbServiceMs)));
            CascadeSimulator.CascadeOutcome outcome =
                    simulator.run(routes(), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);
            for (CascadeSimulator.RouteOutcome route : outcome.routes()) {
                sweep.add(new CascadeSweepPoint(dbServiceMs, route.route(), route.successPct(), route.p99Ms()));
            }
        }

        CascadeSimulator timelineSimulator = new CascadeSimulator(
                topology(ServiceTime.degradedAfter(DEGRADE_AT_MS, DB_HEALTHY_MS, DB_DEGRADED_MS)));
        CascadeSimulator.CascadeOutcome outcome =
                timelineSimulator.run(routes(), durationMs, CLIENT_DEADLINE_MS, WINDOW_MS);
        List<CascadeWindowSample> timeline = toTimeline(outcome);

        double dbHealthyCapacityRps = 1000.0 * DB_WORKERS / DB_HEALTHY_MS;
        return new CascadeRunResult(dbHealthyCapacityRps, sweep, timeline);
    }

    /** The shared-frontend topology, parameterized by the database's service-time curve. */
    static List<ServiceConfig> topology(ServiceTime dbServiceTime) {
        return List.of(
                new ServiceConfig("frontend", FRONTEND_WORKERS, ServiceTime.constant(FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", SERVICE_A_WORKERS, ServiceTime.constant(SERVICE_A_WORK_MS)),
                new ServiceConfig("service-b", SERVICE_B_WORKERS, ServiceTime.constant(SERVICE_B_WORK_MS)),
                new ServiceConfig("database", DB_WORKERS, dbServiceTime));
    }

    /** Both routes at 50 rps each: route a touches the database, route b never does. */
    static List<RouteDemand> routes() {
        return List.of(
                new RouteDemand("route-a", List.of("frontend", "service-a", "database"), ROUTE_RATE_RPS),
                new RouteDemand("route-b", List.of("frontend", "service-b"), ROUTE_RATE_RPS));
    }

    private static List<CascadeWindowSample> toTimeline(CascadeSimulator.CascadeOutcome outcome) {
        CascadeSimulator.RouteOutcome routeA = outcome.routes().get(0);
        CascadeSimulator.RouteOutcome routeB = outcome.routes().get(1);
        List<Integer> frontend = queueOf(outcome, "frontend");
        List<Integer> serviceA = queueOf(outcome, "service-a");
        List<Integer> serviceB = queueOf(outcome, "service-b");
        List<Integer> database = queueOf(outcome, "database");

        int windows = routeA.windowSuccessPct().size();
        List<CascadeWindowSample> timeline = new ArrayList<>(windows);
        for (int w = 0; w < windows; w++) {
            timeline.add(new CascadeWindowSample(
                    (long) w * WINDOW_MS,
                    routeA.windowSuccessPct().get(w),
                    routeB.windowSuccessPct().get(w),
                    frontend.get(w),
                    serviceA.get(w),
                    serviceB.get(w),
                    database.get(w)));
        }
        return timeline;
    }

    private static List<Integer> queueOf(CascadeSimulator.CascadeOutcome outcome, String service) {
        return outcome.services().stream()
                .filter(s -> s.service().equals(service))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing service samples: " + service))
                .queueDepth();
    }
}
