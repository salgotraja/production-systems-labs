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
package dev.engnotes.labs.backpressure.shedding;

/**
 * The shedding policies compared in this experiment: which work gets served and which gets
 * abandoned when the server cannot keep up. Each policy is a combination of three mechanisms in
 * one event loop: pick order (oldest vs newest first), a door bound (reject when the queue is
 * full), and dequeue expiry (discard already-doomed work free of charge).
 */
public enum ShedPolicy {

    /** No shedding at all: unbounded FIFO. The Post 1 baseline - collapses under overload. */
    FIFO("fifo", false, false, false),

    /**
     * FIFO behind a queue cap sized to the deadline budget: excess arrivals are fast-failed at
     * the door (Post 2's gate at the queue). Needs the knob sized to {@code capacity x deadline}.
     */
    TAIL_DROP("tail-drop", false, true, false),

    /**
     * Deadline-aware FIFO: at dequeue, work that can no longer finish inside the deadline is
     * discarded without burning a service slot. Self-tuning - no knob, the deadline is the knob.
     */
    EXPIRE("expire", false, false, true),

    /**
     * Newest-first: always serve the freshest request; the old are shed by starvation and are
     * never told. Keeps served latency low through any backlog, but zombies at the bottom of the
     * stack get served very late once load drops (pure LIFO has no expiry).
     */
    LIFO("lifo", true, false, false);

    private final String label;
    private final boolean pickNewest;
    private final boolean boundedQueue;
    private final boolean dequeueExpiry;

    ShedPolicy(String label, boolean pickNewest, boolean boundedQueue, boolean dequeueExpiry) {
        this.label = label;
        this.pickNewest = pickNewest;
        this.boundedQueue = boundedQueue;
        this.dequeueExpiry = dequeueExpiry;
    }

    public String label() {
        return label;
    }

    public boolean pickNewest() {
        return pickNewest;
    }

    public boolean boundedQueue() {
        return boundedQueue;
    }

    public boolean dequeueExpiry() {
        return dequeueExpiry;
    }
}
