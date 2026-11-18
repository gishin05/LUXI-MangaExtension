plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.multisrc.madara"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            kotlin.srcDirs("src")
            java.srcDirs("src")
        }
    }
}

dependencies {
    compileOnly(libs.keiyoushi.extensions.lib)
    compileOnly(libs.jspecify)
    compileOnly(libs.okhttp)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.injekt)
    compileOnly(libs.rxjava)
}
