/*
 * Copyright 2026 engnotes.dev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.engnotes.labs.commons;

import dev.engnotes.labs.commons.concurrency.ScopedRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopedRunnerTest {

    @Test
    void fanOutReturnsResultsInForkOrder() {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);
        assertEquals(List.of(1, 2, 3), ScopedRunner.fanOut(tasks));
    }

    @Test
    void fanOutPropagatesTaskFailureAsRuntimeException() {
        List<Callable<Integer>> tasks = List.of(
                () -> 1,
                () -> { throw new IllegalStateException("boom"); });
        assertThrows(RuntimeException.class, () -> ScopedRunner.fanOut(tasks));
    }

    @Test
    void hedgeReturnsSuccessfulResult() {
        assertEquals(42, ScopedRunner.hedge(() -> 42, () -> 42));
    }

    @Test
    void hedgeReturnsSecondaryWhenPrimaryFails() {
        // anySuccessfulResultOrThrow: primary throws, secondary succeeds -> 7.
        Integer result = ScopedRunner.hedge(
                () -> { throw new RuntimeException("primary down"); },
                () -> 7);
        assertEquals(7, result);
    }
}
