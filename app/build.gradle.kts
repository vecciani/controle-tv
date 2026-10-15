import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versao = Properties().apply {
    rootProject.file("versao.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.controletv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.controletv"
        minSdk = 25      // Fire OS 6 (Android 7.1)
        targetSdk = 30   // Fire OS 8 (Android 11)
        versionCode = versao.getProperty("apkVersion").toInt()
        versionName = "${versao.getProperty("apkVersion")}.${versao.getProperty("webVersion")}"
        buildConfigField("int", "WEB_VERSION", versao.getProperty("webVersion"))
        buildConfigField("String", "UPDATE_URL", "\"${versao.getProperty("updateUrl")}\"")
    }

    signingConfigs {
        // Every version must be signed with the same key, or the TV refuses the update
        getByName("debug") {
            storeFile = rootProject.file("keystore/controle-tv.keystore")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.mobile:dadb:1.2.10")
}
