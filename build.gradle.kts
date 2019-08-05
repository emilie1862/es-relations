// build.gradle.kts

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "Example"
version = "1.0-SNAPSHOT"

plugins {
    application
    kotlin("jvm") version "1.3.40"
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().all {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "TestAppKt"
}

tasks {
    test {
        useJUnitPlatform()
    }

//    register("runExample", JavaExec::class) {
//        main = "TestAppKt"
////        classpath = sourceSets.main
//    }
}


dependencies {
    compile(kotlin("stdlib"))
    compile(kotlin("reflect"))
    compile("com.fasterxml.jackson.core:jackson-databind:2.9.8")
    compile("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")
    compile("ch.qos.logback:logback-classic:1.2.3")
    compile("org.elasticsearch.client:elasticsearch-rest-high-level-client:7.2.0")
    implementation("com.google.code.gson:gson:2.8.5")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.4.2")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:5.4.2")
}
