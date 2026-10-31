plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.paperweight) apply false
    alias(libs.plugins.runpaper) apply false
}

allprojects {
    group = "io.github.mioyi.agentcraft"
    version = "0.1.0"
}
