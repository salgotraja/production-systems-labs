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
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.xchart)

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
        group = "latency-lab"
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
        group = "latency-lab"
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
    ExperimentTask(1, "runTailLatency", "Tail latency and fan-out amplification", "dev.engnotes.labs.latency.TailLatencyMain"),
    ExperimentTask(2, "runQueueSaturation", "Queue saturation and Little's Law", "dev.engnotes.labs.latency.QueueSaturationMain"),
    ExperimentTask(3, "runHedgedRequests", "Hedged requests and speculative execution", "dev.engnotes.labs.latency.HedgedRequestsMain"),
    ExperimentTask(4, "runCoordinatedOmission", "Coordinated omission measurement", "dev.engnotes.labs.latency.CoordinatedOmissionMain"),
    ExperimentTask(5, "runBackpressure", "Backpressure strategy comparison", "dev.engnotes.labs.latency.BackpressureMain"),
    ExperimentTask(6, "runSloPolicy", "SLO policy and burn-rate simulation", "dev.engnotes.labs.latency.SloPolicyMain"),
)

experiments.forEach { experiment ->
    registerExperimentTask(experiment.taskName, experiment.title, experiment.mainClassName)
    registerPostAlias(experiment.postNumber, experiment.taskName, experiment.title)
}
