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
package dev.engnotes.labs.commons.concurrency;

/**
 * Callback for tracking "wasted" work in the hedged-request pattern.
 * <p>
 * A hedged request fires two identical tasks and cancels the slower one.
 * If the slower task completes before cancellation takes effect, the work is wasted.
 * This counter lets Post 3 build the cost table:
 * <em>hedge at p95 = ~5% extra load</em>.
 */
@FunctionalInterface
public interface WastedWorkCounter {

    /** Called when a hedged secondary task completed despite cancellation being requested. */
    void increment();

    /** A no-op counter for callers that don't need wasted-work tracking. */
    static WastedWorkCounter noop() {
        return () -> {};
    }
}
