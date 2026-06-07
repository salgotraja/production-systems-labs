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
package dev.engnotes.labs.failprop.cascade;

import java.util.List;

/**
 * Client demand for one route: a fixed arrival rate walking a linear call chain. Each service
 * in the chain calls the next synchronously, so the chain is also the worker-hold chain - the
 * client's request holds one worker at every hop until the leaf completes.
 *
 * <p>Linear chains are all Post 1 needs: the cascade comes from two chains <em>sharing</em> a
 * service (the frontend), not from fan-out within one request.
 */
public record RouteDemand(String name, List<String> chain, double rateRps) {

    public RouteDemand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("chain must not be empty");
        }
        if (rateRps <= 0) {
            throw new IllegalArgumentException("rateRps must be > 0");
        }
        chain = List.copyOf(chain);
    }
}
