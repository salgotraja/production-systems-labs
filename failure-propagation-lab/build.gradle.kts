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

plugins {
    id("dev.engnotes.java-conventions")
}

group = "dev.engnotes.labs"

dependencies {
    implementation(project(":lab-commons"))
    implementation(libs.hdrhistogram)
    implementation(libs.xchart)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.javalin)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test>().configureEach {
    systemProperty("golden.dir", rootProject.projectDir.resolve("golden").absolutePath)
    workingDir = rootProject.projectDir
}

fun registerExperimentTask(taskName: String, title: String, mainClassName: String) {
    tasks.register<JavaExec>(taskName) {
        group = "failure-propagation-lab"
        description = "Run experiment: $title"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassName)
        workingDir = rootProject.projectDir
        if (project.hasProperty("args")) {
            args = (project.property("args") as String).split(" ")
        }
    }
}

fun registerPostAlias(number: Int, targetTask: String, title: String) {
    tasks.register("runPost$number") {
        group = "failure-propagation-lab"
        description = "Compatibility alias for $targetTask ($title)"
        dependsOn(targetTask)
    }
}

data class ExperimentTask(
    val postNumber: Int,
    val taskName: String,
    val title: String,
    val mainClassName: String,
)

val experiments = listOf(
    ExperimentTask(1, "runCascadingFailures", "Cascading failures explained", "dev.engnotes.labs.failprop.CascadingFailuresMain"),
    ExperimentTask(2, "runRetryStorms", "Retry storms and amplification", "dev.engnotes.labs.failprop.RetryStormsMain"),
    ExperimentTask(3, "runCircuitBreaker", "Circuit breaker design", "dev.engnotes.labs.failprop.CircuitBreakerMain"),
)

experiments.forEach { experiment ->
    registerExperimentTask(experiment.taskName, experiment.title, experiment.mainClassName)
    registerPostAlias(experiment.postNumber, experiment.taskName, experiment.title)
}

// Post 3's Javalin live mode (ADR-007): real HTTP, wall clock, never golden-tested, not in CI.
tasks.register<JavaExec>("runCircuitBreakerLive") {
    group = "failure-propagation-lab"
    description = "Run the circuit-breaker live demo (two local Javalin services; trip it with curl)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.engnotes.labs.failprop.CircuitBreakerLiveMain")
    workingDir = rootProject.projectDir
}
