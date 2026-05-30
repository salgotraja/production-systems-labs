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
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeasurementModeTest {

    @Test
    void parseDefaultsToBoth() {
        assertEquals(MeasurementMode.BOTH, MeasurementMode.parse(null));
        assertEquals(MeasurementMode.BOTH, MeasurementMode.parse(""));
    }

    @Test
    void parseAcceptsSupportedModes() {
        assertEquals(MeasurementMode.BOTH, MeasurementMode.parse("both"));
        assertEquals(MeasurementMode.CLOSED_LOOP, MeasurementMode.parse("closed-loop"));
        assertEquals(MeasurementMode.OPEN_LOOP, MeasurementMode.parse("OPEN-LOOP"));
    }

    @Test
    void parseRejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> MeasurementMode.parse("raw"));
    }
}
