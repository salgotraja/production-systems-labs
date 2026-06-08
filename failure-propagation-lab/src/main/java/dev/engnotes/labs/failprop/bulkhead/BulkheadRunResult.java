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
package dev.engnotes.labs.failprop.bulkhead;

import java.util.List;

/** Full failure-isolation run: the policy comparison plus the bulkhead sizing sweep. */
public record BulkheadRunResult(
        List<BulkheadPolicyPoint> policies,
        List<BulkheadSweepPoint> sizing) {

    public BulkheadRunResult {
        policies = List.copyOf(policies);
        sizing = List.copyOf(sizing);
    }
}
