plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.rescuecodec"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
