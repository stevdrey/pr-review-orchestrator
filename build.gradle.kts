import org.springframework.boot.gradle.plugin.SpringBootPlugin

buildscript {
    configurations.classpath { resolutionStrategy.activateDependencyLocking() }
}

plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "io.github.stevdrey"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(27) }
}

repositories { mavenCentral() }

dependencyLocking { lockAllConfigurations() }

dependencies {
    val bom = platform(SpringBootPlugin.BOM_COORDINATES)
    implementation(bom)
    annotationProcessor(bom)
    developmentOnly(bom)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
