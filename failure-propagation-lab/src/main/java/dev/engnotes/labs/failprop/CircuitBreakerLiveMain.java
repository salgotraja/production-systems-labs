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
package dev.engnotes.labs.failprop;

import dev.engnotes.labs.failprop.breaker.BreakerConfig;
import dev.engnotes.labs.failprop.breaker.CircuitBreaker;
import io.javalin.Javalin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Javalin live mode promised by ADR-007: the same hand-rolled {@link CircuitBreaker} class
 * the golden experiment uses, now on the wall clock, guarding real HTTP between two local
 * services - so you can trip it with curl and watch it recover.
 *
 * <p><strong>Not golden-tested, not run in CI.</strong> This is the demonstration mode; the
 * numbers in the post come from the deterministic simulation. Run it with:
 * <pre>
 *   ./gradlew :failure-propagation-lab:runCircuitBreakerLive
 * </pre>
 *
 * <p>Then, in another terminal:
 * <pre>
 *   curl localhost:7070/checkout          # 200 - the backend is healthy
 *   curl -X POST "localhost:7071/degrade?ms=800"
 *   curl localhost:7070/checkout          # times out, retries... then the breaker opens
 *   curl localhost:7070/checkout          # 503 in ~0ms - fail fast, backend untouched
 *   curl localhost:7070/status            # breaker state + windowed failure rate
 *   curl -X POST "localhost:7071/degrade?ms=10"
 *   sleep 2; curl localhost:7070/checkout # the next call is the probe - watch it close
 * </pre>
 */
public final class CircuitBreakerLiveMain {

    private static final int FRONTEND_PORT = 7070;
    private static final int BACKEND_PORT = 7071;
    private static final long CALL_TIMEOUT_MS = 400L;
    private static final int ATTEMPTS = 3;
    private static final long BACKOFF_MS = 50L;
    /** Live tuning: a small window and minimum so a few curls are enough to trip it by hand. */
    private static final BreakerConfig BREAKER_CONFIG = new BreakerConfig(50.0, 6, 3, 5_000L, 1);

    private CircuitBreakerLiveMain() {}

    /**
     * Starts the backend and frontend services and blocks until the process is interrupted.
     *
     * @param args ignored; ports and tuning are fixed for the demo
     * @throws InterruptedException if the main thread is interrupted while parked
     */
    public static void main(String[] args) throws InterruptedException {
        AtomicLong backendServiceMs = new AtomicLong(10L);
        Javalin backend = Javalin.create()
                .get("/work", ctx -> {
                    Thread.sleep(backendServiceMs.get());
                    ctx.result("ok");
                })
                .post("/degrade", ctx -> {
                    long ms = Long.parseLong(ctx.queryParam("ms"));
                    backendServiceMs.set(ms);
                    ctx.result("backend service time set to " + ms + "ms\n");
                })
                .start(BACKEND_PORT);

        CircuitBreaker breaker = new CircuitBreaker(BREAKER_CONFIG);
        Object breakerLock = new Object();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                .build();
        HttpRequest workRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + BACKEND_PORT + "/work"))
                .timeout(Duration.ofMillis(CALL_TIMEOUT_MS))
                .build();

        Javalin frontend = Javalin.create()
                .get("/checkout", ctx -> {
                    long started = System.nanoTime();
                    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                        if (attempt > 1) {
                            Thread.sleep(BACKOFF_MS);
                        }
                        boolean allowed;
                        synchronized (breakerLock) {
                            allowed = breaker.allow(nowMs());
                        }
                        if (!allowed) {
                            log("checkout attempt %d: breaker %s - failing fast", attempt, breaker.state());
                            continue;
                        }
                        try {
                            HttpResponse<String> response =
                                    client.send(workRequest, HttpResponse.BodyHandlers.ofString());
                            if (response.statusCode() == 200) {
                                synchronized (breakerLock) {
                                    breaker.onSuccess(nowMs());
                                }
                                ctx.result(String.format(Locale.ROOT, "ok in %dms (attempt %d)%n",
                                        elapsedMs(started), attempt));
                                return;
                            }
                            reportFailure(breaker, breakerLock, attempt, "status " + response.statusCode());
                        } catch (IOException e) {
                            reportFailure(breaker, breakerLock, attempt, "timeout/error");
                        }
                    }
                    ctx.status(503).result(String.format(Locale.ROOT,
                            "failed in %dms after %d attempts (breaker %s)%n",
                            elapsedMs(started), ATTEMPTS, breaker.state()));
                })
                .get("/status", ctx -> ctx.result(String.format(Locale.ROOT,
                        "breaker=%s failure_rate=%.0f%%%n", breaker.state(), breaker.failureRatePct())))
                .start(FRONTEND_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            frontend.stop();
            backend.stop();
        }));

        System.out.println();
        System.out.println("=================================================");
        System.out.println("  Circuit breaker live mode (Ctrl+C to stop)");
        System.out.println("=================================================");
        System.out.printf("  frontend: http://localhost:%d/checkout  /status%n", FRONTEND_PORT);
        System.out.printf("  backend:  http://localhost:%d/work      /degrade?ms=N%n", BACKEND_PORT);
        System.out.println();
        System.out.println("  Try:");
        System.out.println("    curl localhost:7070/checkout");
        System.out.println("    curl -X POST \"localhost:7071/degrade?ms=800\"");
        System.out.println("    curl localhost:7070/checkout   (repeat until the breaker opens)");
        System.out.println("    curl localhost:7070/status");
        System.out.println("    curl -X POST \"localhost:7071/degrade?ms=10\"");
        System.out.println("    sleep 5; curl localhost:7070/checkout   (the probe closes it)");
        System.out.println("=================================================");

        Thread.currentThread().join();
    }

    private static void reportFailure(CircuitBreaker breaker, Object lock, int attempt, String why) {
        synchronized (lock) {
            breaker.onFailure(nowMs());
        }
        log("checkout attempt %d: %s - breaker now %s (failure rate %.0f%%)",
                attempt, why, breaker.state(), breaker.failureRatePct());
    }

    private static void log(String format, Object... args) {
        System.out.printf(Locale.ROOT, "  [live] " + format + "%n", args);
    }

    private static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0);
    }
}
