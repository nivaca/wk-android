// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
}

// La clave de firma está fuera de git, en keystore.properties (véase el
// README).  Sin ese archivo, como en el servidor de F-Droid, que firma con
// su propia clave, la versión release sale sin firmar.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply {
    if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.nivaca.wk"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.nivaca.wk"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreFile.isFile) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // F-Droid pide no incluir este bloque, que va cifrado con una clave de Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Al final de cada versión release firmada, el APK se copia también a
// dist/ como wk-VERSIÓN.apk, en lugar de los anteriores, para que dist/
// tenga siempre el más nuevo.  Sin firma (como en F-Droid) no se copia nada.
val distApk = tasks.register("distApk") {
    doLast {
        val version = android.defaultConfig.versionName
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!apk.isFile) return@doLast
        val dist = rootProject.file("dist")
        dist.mkdirs()
        dist.listFiles { f -> f.name.startsWith("wk-") && f.name.endsWith(".apk") }?.forEach { it.delete() }
        apk.copyTo(File(dist, "wk-$version.apk"), overwrite = true)
        println("dist/wk-$version.apk")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(distApk) }
