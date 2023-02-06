import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.4.4")
    implementation("org.slf4j:slf4j-api:2.0.3")

    implementation("io.ktor:ktor-server-core:2.1.2")
    implementation("io.ktor:ktor-server-netty:2.1.2")
    implementation("io.ktor:ktor-server-status-pages:2.1.2")
    implementation("io.ktor:ktor-client-core:2.1.2")
    implementation("io.ktor:ktor-client-cio:2.1.2")
    implementation("io.ktor:ktor-server-html-builder:2.1.2")
    implementation("io.ktor:ktor-server-auth:2.1.2")
    implementation("io.ktor:ktor-server-sessions:2.1.2")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:2.1.2")
    implementation("io.ktor:ktor-server-cors-jvm:2.1.2")

    implementation("com.typesafe:config:1.4.2")

    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("com.h2database:h2:2.1.214")
    implementation("org.flywaydb:flyway-core:9.5.1")
    implementation("com.github.seratch:kotliquery:1.9.0")

    implementation("com.google.code.gson:gson:2.10")
    implementation("io.arrow-kt:arrow-fx-coroutines:1.1.2")
    implementation("io.arrow-kt:arrow-fx-stm:1.1.2")
    implementation("at.favre.lib:bcrypt:0.9.0")

    // Chapter 12
    implementation("com.amazonaws:aws-lambda-java-core:1.2.1")

    // Chapter 13
    implementation("org.springframework:spring-context:5.3.23")

    // Chapter 14
    implementation("io.ktor:ktor-server-servlet:2.1.2")
    implementation("org.eclipse.jetty:jetty-server:9.4.49.v20220914")
    implementation("org.eclipse.jetty:jetty-servlet:9.4.49.v20220914")
    implementation("org.springframework.security:spring-security-web:5.7.3")
    implementation("org.springframework.security:spring-security-config:5.7.3")

    // Appendix A
    implementation("io.jooby:jooby:2.16.1")
    implementation("io.jooby:jooby-netty:2.16.1")

    // Appendix B
    implementation("com.sksamuel.hoplite:hoplite-core:2.6.3")
    implementation("com.sksamuel.hoplite:hoplite-hocon:2.6.3")

    // Appendix C
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:2.0.19")
    testImplementation("org.spekframework.spek2:spek-runner-junit5:2.0.19")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("kotlinbook.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}