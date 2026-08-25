/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The agent loads into the target project's test JVM, so targeting the toolchain's 21 would abort a
// JDK 17 test JVM with UnsupportedClassVersionError during premain.
tasks.withType<JavaCompile>().configureEach {
    options.release = 11
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.18.12")
}

tasks.shadowJar {
    archiveBaseName.set("opa-ir-coverage-agent")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes(
            // Attaches only at JVM startup
            "Premain-Class" to "org.openpolicyagent.coverage.agent.OpaIrCoverageAgent",
            "Can-Retransform-Classes" to "true",
        )
    }

    relocate("net.bytebuddy", "org.openpolicyagent.coverage.agent.shadow.bytebuddy")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
