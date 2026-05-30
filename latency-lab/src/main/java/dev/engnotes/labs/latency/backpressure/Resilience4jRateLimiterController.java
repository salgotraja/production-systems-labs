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
package dev.engnotes.labs.latency.backpressure;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

public final class Resilience4jRateLimiterController implements BackpressureController {

    private final int limitForPeriod;
    private final long serviceTimeMs;
    private long currentSecond;
    private RateLimiter rateLimiter;

    public Resilience4jRateLimiterController(int limitForPeriod, long serviceTimeMs) {
        if (limitForPeriod < 1 || serviceTimeMs < 1) {
            throw new IllegalArgumentException("limitForPeriod and serviceTimeMs must be >= 1");
        }
        this.limitForPeriod = limitForPeriod;
        this.serviceTimeMs = serviceTimeMs;
        this.currentSecond = -1L;
        this.rateLimiter = newRateLimiter(0L);
    }

    @Override
    public BackpressureStrategy strategy() {
        return BackpressureStrategy.RESILIENCE4J_RATE_LIMITER;
    }

    @Override
    public BackpressureDecision admit(long arrivalMs) {
        long second = arrivalMs / 1_000L;
        if (second != currentSecond) {
            currentSecond = second;
            rateLimiter = newRateLimiter(second);
        }
        return rateLimiter.acquirePermission()
                ? BackpressureDecision.accepted(serviceTimeMs)
                : BackpressureDecision.rejected();
    }

    @Override
    public int buffered() {
        return 0;
    }

    private RateLimiter newRateLimiter(long second) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of("post5-" + second, config);
    }
}
