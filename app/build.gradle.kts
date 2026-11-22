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
        versionCode = 3
        versionName = "1.2"
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
// dist/ como wiktionary-reader-VERSIÓN.apk, en lugar de los anteriores,
// para que dist/ tenga siempre el más nuevo.  Sin firma (como en F-Droid)
// no se copia nada.
//
// Además, si gpg tiene la clave secreta del proyecto, el APK de dist/
// lleva una firma OpenPGP aparte (.apk.asc), como los paquetes de py-wk.
// La clave es SIGNING_KEY, o la que diga la variable WK_GPG_KEY; gpg pide
// su contraseña (o la toma de gpg-agent, si la recuerda).  Sin la clave,
// el APK queda solo con su firma de Android.

// The project's OpenPGP key (Nicolas Vaughan, ed25519, 2026), the same
// as py-wk's; its public half is GPG-KEY-wiktionary-reader.asc.
val SIGNING_KEY = "81870643CA221AF90C30554EBE6832CD35994948"

fun run(vararg command: String): Pair<Int, String> {
    val p = ProcessBuilder(*command).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    return p.waitFor() to out
}

val distApk = tasks.register("distApk") {
    doLast {
        val version = android.defaultConfig.versionName
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!apk.isFile) return@doLast
        val dist = rootProject.file("dist")
        dist.mkdirs()
        dist.listFiles { f ->
            (f.name.startsWith("wk-") || f.name.startsWith("wiktionary-reader-")) &&
                (f.name.endsWith(".apk") || f.name.endsWith(".apk.asc"))
        }?.forEach { it.delete() }
        val out = File(dist, "wiktionary-reader-$version.apk")
        apk.copyTo(out, overwrite = true)
        println("dist/${out.name}")

        // F-Droid compila la etiqueta vVERSIÓN y compara su APK con este
        // (compilación reproducible); el APK guarda el commit del que sale,
        // así que solo coincide si se compiló desde la etiqueta, sin cambios.
        val root = rootProject.projectDir.path
        val tag = "v$version"
        val head = try { run("git", "-C", root, "rev-parse", "HEAD").second.trim() } catch (_: java.io.IOException) { "" }
        val tagged = try { run("git", "-C", root, "rev-parse", "--verify", "-q", "$tag^{commit}") } catch (_: java.io.IOException) { 1 to "" }
        val dirty = try { run("git", "-C", root, "status", "--porcelain", "--untracked-files=no").second.isNotBlank() } catch (_: java.io.IOException) { false }
        when {
            tagged.first != 0 ->
                println("Note: there is no tag $tag yet; F-Droid will compare its build of $tag with the APK of that release.")
            tagged.second.trim() != head || dirty ->
                println("WARNING: this APK is not built from the tag $tag" +
                        (if (dirty) " (the work tree has changes)" else "") +
                        ", so it will not match F-Droid's build. For the release, build from $tag.")
            else -> println("Built from the tag $tag.")
        }

        val key =System.getenv("WK_GPG_KEY")?.takeIf { it.isNotBlank() } ?: SIGNING_KEY
        val hasKey = try {
            run("gpg", "--batch", "--list-secret-keys", key).first == 0
        } catch (_: java.io.IOException) {
            false                                  // no gpg at all
        }
        if (!hasKey) {
            println("Not signed with OpenPGP: gpg has no secret key $key")
            return@doLast
        }
        val (code, text) = run("gpg", "--batch", "--yes", "--armor", "--local-user", key,
                                "--detach-sign", out.path)
        if (code != 0) throw GradleException("gpg could not sign ${out.name}:\n$text")
        val (ok, check) = run("gpg", "--batch", "--verify", out.path + ".asc", out.path)
        if (ok != 0) throw GradleException("the signature of ${out.name} does not verify:\n$check")
        println("dist/${out.name}.asc")
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(distApk) }
