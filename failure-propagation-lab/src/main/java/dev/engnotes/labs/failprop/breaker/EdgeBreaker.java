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

import java.util.List;

/**
 * What the simulator needs from a breaker guarding one call edge - implemented by the
 * hand-rolled {@link CircuitBreaker} and by the {@link Resilience4jBreakerAdapter}, so the
 * same experiment can show the mechanics first and the library doing the same thing second.
 */
public interface EdgeBreaker {

    /** Gate a call at {@code nowMs}: {@code true} means proceed, {@code false} means fail fast. */
    boolean allow(double nowMs);

    /** Report a successful call outcome observed at {@code nowMs}. */
    void onSuccess(double nowMs);

    /** Report a failed call outcome observed at {@code nowMs}. */
    void onFailure(double nowMs);

    /** Every state change so far, oldest first. */
    List<CircuitBreaker.Transition> transitions();
}
