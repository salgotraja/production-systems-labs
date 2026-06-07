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
package dev.engnotes.labs.backpressure.slocontrol;

/**
 * How the bounded system treats request criticality when it must shed. Both policies run the
 * same bounded architecture (in-system door bound + dequeue expiry); the only difference is
 * whether the door knows what a request is worth.
 */
public enum ClassPolicy {

    /**
     * Class-unaware: when the system is full, the arriving request is rejected no matter what it
     * is. Shedding spreads the pain evenly, so the critical class degrades in lockstep with the
     * background class.
     */
    BLIND("blind"),

    /**
     * Criticality-aware: a critical arrival that finds the system full evicts the newest queued
     * background request (pinned for determinism) and takes its place; it is rejected only when
     * the queue holds no background work. Background arrivals are rejected at the full door as
     * usual. The critical class's success SLO is what drives which work sheds.
     */
    PRIORITY("priority");

    private final String label;

    ClassPolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
