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
package dev.engnotes.labs.commons.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;
import java.util.stream.Stream;

/**
 * Thin wrapper around {@link StructuredTaskScope} (Java 25 finalized API).
 * <p>
 * Isolates the StructuredTaskScope API surface so that all labs are insulated from
 * future API changes - only this class needs updating if the JDK API shifts (ADR-004).
 * <p>
 * Two concurrency patterns are exposed:
 * <ul>
 *   <li>{@link #fanOut} - fork N tasks, wait for all to succeed or throw on any failure.
 *       Used by {@code RequestSimulator} to run concurrent virtual clients.</li>
 *   <li>{@link #hedge} - fork two tasks for the same work, return whichever finishes first,
 *       cancel the other. Used by the hedged-requests pattern in Post 3.</li>
 * </ul>
 * This class does NOT abstract executor creation. Each lab calls
 * {@code Executors.newVirtualThreadPerTaskExecutor()} directly for full control.
 */
public final class ScopedRunner {

    private ScopedRunner() {}

    /**
     * Forks {@code tasks}, waits for all to complete successfully, and returns their results
     * as an ordered list. If any task fails, the remaining tasks are cancelled and the
     * first exception is rethrown wrapped in a {@link RuntimeException}.
     *
     * @param tasks callables to fork - each runs in its own virtual thread
     * @param <T>   result type
     * @return results in fork order
     * @throws RuntimeException if any task fails or the current thread is interrupted
     */
    public static <T> List<T> fanOut(List<Callable<T>> tasks) {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<T>allSuccessfulOrThrow())) {
            List<Subtask<T>> subtasks = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                subtasks.add(scope.fork(task));
            }
            scope.join();
            List<T> results = new ArrayList<>(subtasks.size());
            for (Subtask<T> st : subtasks) {
                results.add(st.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("fanOut interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("fanOut failed", e);
        }
    }

    /**
     * Hedged request: forks two callables that represent the same logical operation.
     * Returns the result of whichever completes first and cancels the other.
     * <p>
     * Records the number of "wasted" completions (second task that finished after cancellation
     * was requested but before it was interrupted) via the provided {@link WastedWorkCounter}.
     *
     * @param primary   primary attempt
     * @param secondary hedged attempt (typically launched after a delay by the caller)
     * @param wasted    counter incremented when both tasks complete before cancellation takes effect
     * @param <T>       result type
     * @return result of the first successful completion
     * @throws RuntimeException if both tasks fail or the current thread is interrupted
     */
    public static <T> T hedge(Callable<T> primary, Callable<T> secondary, WastedWorkCounter wasted) {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<T>anySuccessfulResultOrThrow())) {
            Subtask<T> primaryTask = scope.fork(primary);
            Subtask<T> secondaryTask = scope.fork(secondary);
            T result = scope.join();
            // Both tasks completed successfully before cancellation took effect - wasted work
            if (primaryTask.state() == Subtask.State.SUCCESS
                    && secondaryTask.state() == Subtask.State.SUCCESS) {
                wasted.increment();
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("hedge interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("hedge failed: both tasks threw exceptions", e);
        }
    }

    /**
     * Simplified hedge without wasted-work tracking. Forks {@code primary} and {@code secondary},
     * returns the first successful result.
     */
    public static <T> T hedge(Callable<T> primary, Callable<T> secondary) {
        return hedge(primary, secondary, () -> {});
    }

    /**
     * Forks all tasks, waits for all to finish (success or failure), and returns a stream
     * of the subtasks so callers can inspect individual results. No exception is thrown on
     * individual task failures - callers must check {@link Subtask#state()}.
     * <p>
     * Primarily used in Post 4 (coordinated omission demo) where we want to observe both
     * successful and failed measurements.
     */
    public static <T> Stream<Subtask<T>> fanOutCollectAll(List<Callable<T>> tasks) {
        // awaitAll() waits for every subtask regardless of success or failure - correct for
        // scenarios where callers need to inspect individual outcomes (Post 4 coordinated omission).
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            List<Subtask<T>> subtasks = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                subtasks.add(scope.fork(task));
            }
            scope.join();
            return subtasks.stream();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("fanOutCollectAll interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("fanOutCollectAll failed", e);
        }
    }
}
