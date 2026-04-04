plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "ru.krypto.gateway"
version = "0.1.0"

application {
    mainClass.set("ru.krypto.gateway.ApplicationKt")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://packages.confluent.io/maven/")
    maven("https://jitpack.io")
}

val kryptoCommonsVersion = "1.1.29"

dependencies {
    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.rate.limiting)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation("io.ktor:ktor-server-auth-jwt")

    // Ktor Client (proxy to upstream services)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.apache)
    implementation(libs.ktor.client.content.negotiation)
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-websockets")

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    // Kafka
    implementation("org.apache.kafka:kafka-clients:3.9.0")

    // Redis
    implementation("redis.clients:jedis:5.0.2")

    // Jackson
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.2")

    // Health
    implementation(libs.khealth)

    // Logging
    implementation(libs.logback.classic)

    // Krypto Common
    implementation(files("libs/krypto-common-${kryptoCommonsVersion}.jar"))

    // Kotlinx RPC (client only - to call apikey-service, orderbook-service)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.test.junit.launcher)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("kotlin.RequiresOptIn")
    }
}

ktor {
    fatJar {
        archiveFileName.set("app.jar")
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
