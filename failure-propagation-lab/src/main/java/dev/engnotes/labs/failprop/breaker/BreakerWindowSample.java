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
package dev.engnotes.labs.failprop.breaker;

/**
 * One window of the blast-radius timeline: both routes' success under the naive and breakered
 * runs, the database attempt rate each run produced, and the breakered run's edge states
 * (0 = closed, 1 = open, 2 = half-open).
 */
public record BreakerWindowSample(
        long windowStartMs,
        double naiveRouteAPct,
        double naiveRouteBPct,
        double breakerRouteAPct,
        double breakerRouteBPct,
        double naiveDbAttemptsRps,
        double breakerDbAttemptsRps,
        int frontendEdgeState,
        int databaseEdgeState) {}
