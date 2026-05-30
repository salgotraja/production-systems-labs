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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedQueueTest {

    @Test
    void queuesUntilCapacityThenRejects() {
        BoundedQueue queue = new BoundedQueue(2, 10);

        assertTrue(queue.admit(0).accepted());
        BackpressureDecision queued = queue.admit(1);
        assertTrue(queued.accepted());
        assertEquals(19, queued.latencyMs());
        assertFalse(queue.admit(2).accepted());
    }

    @Test
    void removesCompletedWorkBeforeAdmission() {
        BoundedQueue queue = new BoundedQueue(1, 10);

        assertTrue(queue.admit(0).accepted());
        assertTrue(queue.admit(10).accepted());
    }
}
