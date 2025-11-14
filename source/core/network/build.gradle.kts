plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
}

android {
    namespace = "com.xayah.core.network"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:util"))
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(project(":core:rootservice"))

    // AWS kt SDK
    implementation("aws.sdk.kotlin:s3:1.0.50")

    // 确保有必要的 Kotlin 协程依赖
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // OkHttp
    implementation(libs.okhttp)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Gson
    implementation(libs.gson)

    // Backends
    implementation(libs.apache.commons.net)
    implementation(libs.smbj) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation(libs.guava.compat)
    implementation(libs.smbj.rpc) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "com.hierynomus", module = "smbj")
    }
    implementation(libs.sshj)
    implementation(libs.sardine.next)

    // PickYou
    implementation(libs.pickyou)
}