rootProject.name = "production-systems-labs"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Series 1 — lab-commons and latency-lab included. Unreleased labs exist in the repo but are not listed here.
include("lab-commons")
include("latency-lab")
