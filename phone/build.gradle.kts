import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versao = Properties().apply {
    rootProject.file("versao.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.controletv.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.controletv.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = versao.getProperty("phoneVersion").toInt()
        versionName = versao.getProperty("phoneVersion")
        buildConfigField("String", "UPDATE_URL", "\"${versao.getProperty("updateUrl")}\"")
    }

    signingConfigs {
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
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.squareup.okio:okio:2.10.0")
}
