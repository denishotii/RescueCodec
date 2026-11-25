// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
}

group = "org.rescuecodec"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

val specDir = rootProject.layout.projectDirectory.dir("../spec")

sourceSets {
    main {
        proto {
            // The one wire definition lives in spec/, never copied.
            srcDir(specDir)
            include("rescuecodec/**/*.proto")
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                getByName("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
    testImplementation(kotlin("test"))
}

// Word files for readable text are bundled from spec/i18n so the library works offline.
tasks.processResources {
    from(specDir.dir("i18n")) {
        include("*.json")
        into("rescuecodec/i18n")
    }
}

tasks.test {
    useJUnit()
    systemProperty("rescuecodec.spec", specDir.asFile.absolutePath)
}
