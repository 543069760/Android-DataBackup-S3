plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.protobuf)
    alias(libs.plugins.refine)

    // 插件别名 'serialization' 是正确的，保持不变
    alias(libs.plugins.serialization)
}

android {
    namespace = "com.xayah.core.service"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:util"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:rootservice"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    compileOnly(project(":core:hiddenapi"))

    // Gson
    implementation(libs.gson)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)

    // 关键修复：显式添加 kotlinx-serialization-json 运行时库
    // 解决 ResticExecutor.kt 中的 Json/decodeFromString 引用问题
    implementation(libs.kotlinx.json)

    // 保持 Protobuf 库（如果项目中的其他地方需要）
    implementation(libs.serialization.protobuf)
}