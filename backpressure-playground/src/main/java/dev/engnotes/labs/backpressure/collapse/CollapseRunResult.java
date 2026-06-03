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
package dev.engnotes.labs.backpressure.collapse;

import java.util.List;

/**
 * Full result of a collapse experiment: the server capacity under test and one
 * {@link LoadLevelResult} per (mode, offered-load) point in the sweep.
 *
 * @param serverCapacityRps fixed server capacity mu in requests per second
 * @param levels            sweep points, no-retry levels first then retry levels, each ascending by offered load
 */
public record CollapseRunResult(double serverCapacityRps, List<LoadLevelResult> levels) {

    public CollapseRunResult {
        levels = List.copyOf(levels);
    }
}
