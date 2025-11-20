plugins {
    alias(libs.plugins.library.common)
}

android {
    namespace = "com.xayah.feature.flavor.premium"
}

dependencies {
    // Core
    implementation(project(":core:provider"))
}
