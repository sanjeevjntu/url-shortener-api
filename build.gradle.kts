import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.caffeine) // redirect cache + rate-limit bucket store (decision D2)
    implementation(libs.bucket4j) // token-bucket maths for per-client rate limits (decision D8)
    implementation(libs.springdoc.webmvc.ui) // /swagger-ui.html, /v3/api-docs
    runtimeOnly(libs.micrometer.prometheus) // /actuator/prometheus
    runtimeOnly(libs.flyway.postgresql) // Flyway 10+ needs the DB-specific module
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher) // required explicitly since Gradle 9
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all"))
}

springBoot {
    buildInfo() // exposes build time/version via /actuator/info
}

tasks.bootJar {
    archiveFileName = "app.jar" // stable name for the Dockerfile's extract + AOT-cache steps
}

// ---------------------------------------------------------------- quality gates

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(libs.versions.palantirJavaFormat.get())
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

spotbugs {
    toolVersion = libs.versions.spotbugsTool.get()
    effort = Effort.DEFAULT
    reportLevel = Confidence.MEDIUM
    excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.spotbugsMain {
    // Findings in a readable file, not only the console: build/reports/spotbugs/main.html
    reports.create("html") { required = true }
}

tasks.named("spotbugsTest") { enabled = false } // gate production code; tests are reviewed, not bug-scanned

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading") // Mockito/ByteBuddy on recent JDKs
    // Hermetic tests: never the developer's "local" profile from config/application.yml (or their local key file).
    systemProperty("spring.profiles.default", "test")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(classDirectories.files.map { dir -> fileTree(dir) { exclude("**/ShortenerApplication*", "**/config/OpenApiConfig*") } })
    )
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                // Raised from 0.70 (T2) now that services and controllers exist with unit + HTTP integration tests.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
