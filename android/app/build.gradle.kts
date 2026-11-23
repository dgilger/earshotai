plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx (speech recognition, speaker labels, voice detector): prebuilt Android libraries from
// its GitHub release. The Kotlin API in src/main/java/com/k2fsa/sherpa/onnx is copied from the same tag.
val sherpaVersion = "1.13.8"
val sherpaLibs = layout.buildDirectory.dir("sherpa-onnx/jniLibs")
val fetchSherpa by tasks.registering {
    val out = sherpaLibs.get().asFile
    outputs.dir(out)
    onlyIf { !File(out, "arm64-v8a/libsherpa-onnx-jni.so").exists() }
    doLast {
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-v$sherpaVersion-android.tar.bz2"
        val tmp = File(out.parentFile, "libs.tar.bz2")
        out.mkdirs()
        ant.invokeMethod("get", mapOf("src" to url, "dest" to tmp))
        ant.invokeMethod("untar", mapOf("src" to tmp, "dest" to out.parentFile, "compression" to "bzip2"))
        // Keep only what the app loads: the JNI bridge and ONNX Runtime, for arm64.
        out.listFiles()?.filter { it.isDirectory && it.name != "arm64-v8a" }?.forEach { it.deleteRecursively() }
        File(out, "arm64-v8a").listFiles()?.filter { it.name !in setOf("libsherpa-onnx-jni.so", "libonnxruntime.so") }
            ?.forEach { it.delete() }
        tmp.delete()
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

android {
    namespace = "app.earshot"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.earshot"
        minSdk = 29          // Android 10: foreground service types, scoped storage
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }   // every phone fast enough for on-device speech models
    }

    signingConfigs {
        // Committed debug key so each CI build installs over the last one. Not a release key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") { jniLibs.srcDir(sherpaLibs.get().asFile) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { pickFirsts += "**/libc++_shared.so" }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**")
        }
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")   // unpacks the .tar.bz2 speech models
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
