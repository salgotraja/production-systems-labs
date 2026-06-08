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

import java.util.Objects;

/**
 * One service in the topology: a bounded worker pool in front of an unbounded FIFO queue.
 *
 * <p>The bounded pool is the cascade vector this experiment isolates: a synchronous call holds
 * its caller's worker for the entire downstream wait, so a slow dependency exhausts every pool
 * between it and the client. The queue is deliberately unbounded - Post 1 models a system with
 * no admission control, where the failure shows up as deadline misses, not rejections.
 */
public record ServiceConfig(String name, int workers, ServiceTime ownWork) {

    public ServiceConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        Objects.requireNonNull(ownWork, "ownWork");
    }
}
