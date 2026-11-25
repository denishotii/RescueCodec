// SPDX-License-Identifier: Apache-2.0
import org.yaml.snakeyaml.Yaml

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.yaml:snakeyaml:2.3") }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "org.rescuecodec"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":codec"))
    testImplementation(kotlin("test"))
}

val specDir = rootProject.layout.projectDirectory.dir("../spec")
val vocabYaml = specDir.file("vocab/sar.yaml")
val vocabOut = layout.buildDirectory.dir("generated/vocab")

// The vocabulary stays one YAML file in spec/. At build time it becomes JSON, so the library has
// no YAML dependency at run time (small, and safe on Android).
val generateVocab = tasks.register("generateVocab") {
    inputs.file(vocabYaml)
    outputs.dir(vocabOut)
    doLast {
        fun json(v: Any?, sb: StringBuilder) {
            when (v) {
                null -> sb.append("null")
                is Boolean, is Number -> sb.append(v.toString())
                is String -> {
                    sb.append('"')
                    for (c in v) when {
                        c == '"' -> sb.append("\\\"")
                        c == '\\' -> sb.append("\\\\")
                        c < ' ' -> sb.append("\\u%04x".format(c.code))
                        else -> sb.append(c)
                    }
                    sb.append('"')
                }
                is Map<*, *> -> {
                    sb.append('{')
                    var first = true
                    for ((k, x) in v) {
                        if (!first) sb.append(',')
                        first = false
                        json(k.toString(), sb)
                        sb.append(':')
                        json(x, sb)
                    }
                    sb.append('}')
                }
                is Iterable<*> -> {
                    sb.append('[')
                    var first = true
                    for (x in v) {
                        if (!first) sb.append(',')
                        first = false
                        json(x, sb)
                    }
                    sb.append(']')
                }
                else -> json(v.toString(), sb)
            }
        }
        val data = Yaml().load<Any?>(vocabYaml.asFile.readText())
        val sb = StringBuilder()
        json(data, sb)
        val out = vocabOut.get().file("rescuecodec/vocab/sar.json").asFile
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}

tasks.processResources {
    from(generateVocab)
}

tasks.test {
    useJUnit()
    systemProperty("rescuecodec.spec", specDir.asFile.absolutePath)
}
