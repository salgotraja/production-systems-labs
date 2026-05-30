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
package dev.engnotes.labs.latency.measurement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PauseServiceModelTest {

    @Test
    void latencyIsBaselineOutsidePause() {
        PauseServiceModel model = new PauseServiceModel(10, 100, 50);

        assertEquals(10, model.latencyForArrival(90));
        assertEquals(10, model.latencyForArrival(150));
    }

    @Test
    void latencyIncludesWaitUntilPauseEnds() {
        PauseServiceModel model = new PauseServiceModel(10, 100, 50);

        assertEquals(60, model.latencyForArrival(100));
        assertEquals(35, model.latencyForArrival(125));
        assertEquals(11, model.latencyForArrival(149));
    }
}
