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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behaviours that make the cascade demonstration correct: the failure crosses to a
 * route that never touches the slow dependency, the shared frontend pool is the carrier (the
 * isolated-pools control keeps the healthy route at 100%), and the backlog stacks upstream of
 * the bottleneck. If any of these regress, the post no longer demonstrates its own title.
 */
class CascadeSimulatorTest {

    private static final long DURATION_MS = 5_000L;
    private static final long DEADLINE_MS = CascadeScenario.CLIENT_DEADLINE_MS;
    private static final long WINDOW_MS = CascadeScenario.WINDOW_MS;

    private CascadeSimulator.CascadeOutcome run(long dbServiceMs) {
        return new CascadeSimulator(CascadeScenario.topology(ServiceTime.constant(dbServiceMs)))
                .run(CascadeScenario.routes(), DURATION_MS, DEADLINE_MS, WINDOW_MS);
    }

    private static CascadeSimulator.RouteOutcome route(CascadeSimulator.CascadeOutcome outcome, String name) {
        return outcome.routes().stream()
                .filter(r -> r.route().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void healthyTopologyServesEverything() {
        CascadeSimulator.CascadeOutcome outcome = run(10);
        assertEquals(100.0, route(outcome, "route-a").successPct(), 0.01);
        assertEquals(100.0, route(outcome, "route-b").successPct(), 0.01);
        assertTrue(route(outcome, "route-a").p99Ms() <= 25.0, "healthy route-a is frontend+service-a+db work");
    }

    @Test
    void belowCapacityDegradationIsHarmless() {
        // 150ms db service is 75% of database capacity (10 workers / 150ms = 66 rps vs 50 rps
        // demand): slower, but every request still beats the deadline.
        CascadeSimulator.CascadeOutcome outcome = run(150);
        assertEquals(100.0, route(outcome, "route-a").successPct(), 0.01);
        assertEquals(100.0, route(outcome, "route-b").successPct(), 0.01);
    }

    @Test
    void slowDatabaseCollapsesItsOwnRoute() {
        // 500ms db service caps the database at 20 rps against 50 rps demand.
        CascadeSimulator.CascadeOutcome outcome = run(500);
        assertTrue(route(outcome, "route-a").successPct() < 20.0,
                "route-a success: " + route(outcome, "route-a").successPct());
    }

    @Test
    void cascadeReachesTheRouteThatNeverTouchesTheDatabase() {
        // route-b's own services stay healthy; the only thing it shares with route-a is the
        // frontend worker pool. That alone takes it down.
        CascadeSimulator.CascadeOutcome outcome = run(500);
        CascadeSimulator.RouteOutcome routeB = route(outcome, "route-b");
        assertTrue(routeB.successPct() < 30.0, "route-b success: " + routeB.successPct());
        assertTrue(routeB.p99Ms() > 1_000.0,
                "route-b p99 must blow past its 15ms healthy latency: " + routeB.p99Ms());
    }

    @Test
    void isolatedFrontendPoolsContainTheBlast() {
        // The bulkhead control (Post 5's cure, used here only as proof of mechanism): give each
        // route its own frontend pool and the same 500ms database leaves route-b untouched.
        List<ServiceConfig> isolated = List.of(
                new ServiceConfig("frontend-a", 10, ServiceTime.constant(CascadeScenario.FRONTEND_WORK_MS)),
                new ServiceConfig("frontend-b", 10, ServiceTime.constant(CascadeScenario.FRONTEND_WORK_MS)),
                new ServiceConfig("service-a", 10, ServiceTime.constant(CascadeScenario.SERVICE_A_WORK_MS)),
                new ServiceConfig("service-b", 10, ServiceTime.constant(CascadeScenario.SERVICE_B_WORK_MS)),
                new ServiceConfig("database", 10, ServiceTime.constant(500)));
        List<RouteDemand> routes = List.of(
                new RouteDemand("route-a", List.of("frontend-a", "service-a", "database"), 50.0),
                new RouteDemand("route-b", List.of("frontend-b", "service-b"), 50.0));

        CascadeSimulator.CascadeOutcome outcome =
                new CascadeSimulator(isolated).run(routes, DURATION_MS, DEADLINE_MS, WINDOW_MS);

        assertTrue(route(outcome, "route-a").successPct() < 20.0, "route-a still collapses");
        assertEquals(100.0, route(outcome, "route-b").successPct(), 0.01,
                "with no shared pool there is no path for the failure to travel");
    }

    @Test
    void backlogQueuesUpstreamOfTheBottleneck() {
        // The slow database's own queue stays near zero (service-a's pool gates what reaches
        // it); the backlog stacks at the services in front of it.
        CascadeSimulator simulator = new CascadeSimulator(CascadeScenario.topology(
                ServiceTime.degradedAfter(CascadeScenario.DEGRADE_AT_MS,
                        CascadeScenario.DB_HEALTHY_MS, CascadeScenario.DB_DEGRADED_MS)));
        CascadeSimulator.CascadeOutcome outcome =
                simulator.run(CascadeScenario.routes(), DURATION_MS, DEADLINE_MS, WINDOW_MS);

        int maxFrontend = maxQueue(outcome, "frontend");
        int maxDatabase = maxQueue(outcome, "database");
        assertTrue(maxFrontend >= 50, "frontend backlog grows without bound: " + maxFrontend);
        assertTrue(maxDatabase <= 1, "database queue stays near zero: " + maxDatabase);
    }

    @Test
    void rejectsWindowsShorterThanTheDeadline() {
        CascadeSimulator simulator = new CascadeSimulator(CascadeScenario.topology(ServiceTime.constant(10)));
        assertThrows(IllegalArgumentException.class,
                () -> simulator.run(CascadeScenario.routes(), DEADLINE_MS, DEADLINE_MS, WINDOW_MS));
    }

    @Test
    void outputIsDeterministic() {
        assertEquals(run(300), run(300), "identical inputs must produce identical results");
    }

    private static int maxQueue(CascadeSimulator.CascadeOutcome outcome, String service) {
        return outcome.services().stream()
                .filter(s -> s.service().equals(service))
                .findFirst()
                .orElseThrow()
                .queueDepth()
                .stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }
}
