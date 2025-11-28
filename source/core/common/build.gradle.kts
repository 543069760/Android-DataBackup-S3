plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.compose)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.xayah.core.common"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}