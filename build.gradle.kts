import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
    `maven-publish`
}

group = "dev.aetherx86.pyrite"
version = "0.1.0"

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation("org.ow2.asm:asm:9.11.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.aetherx86.pyrite.MainKt")
    applicationName = "pyrite"
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "pyrite"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Aetherx86/Pyrite")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
