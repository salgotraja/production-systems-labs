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

/**
 * One window of the storm timeline: client success and database attempt rate, side by side for
 * the no-retry (R=1) and retrying (R=3) runs over the same transient degradation.
 */
public record StormWindowSample(
        long windowStartMs,
        double r1SuccessPct,
        double r3SuccessPct,
        double r1DbAttemptsRps,
        double r3DbAttemptsRps) {}
