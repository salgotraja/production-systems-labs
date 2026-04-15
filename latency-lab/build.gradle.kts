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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.register<JavaExec>("runPost1") {
    group = "latency-lab"
    description = "Run Post 1: Why Average Latency Lies"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.engnotes.labs.latency.Post1Main")
    // Run from the repo root so --output-dir ./results resolves relative to the root
    workingDir = rootProject.projectDir
    // Allow --args="..." from command line
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split(" ")
    }
}
