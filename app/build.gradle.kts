import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// The full ORT AAR is used as a source artifact only. Its ARM64 runtime replaces the minimal
// ONNX-format-incompatible library embedded in Moonshine; do not package a second runtime.
val fullOrtRuntimeAar = configurations.create("fullOrtRuntimeAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val generatedOrtJniDirectory = layout.buildDirectory.dir("generated/ort-runtime-jni")
val generatedOrtArm64Library = generatedOrtJniDirectory.map { it.file("arm64-v8a/libonnxruntime.so") }
val fullOrtArm64Sha256 = "e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302"
val extractFullOrtRuntime = tasks.register("extractFullOrtRuntime") {
    inputs.files(fullOrtRuntimeAar)
    outputs.file(generatedOrtArm64Library)
    doLast {
        val archiveFile = fullOrtRuntimeAar.singleFile
        val destination = generatedOrtArm64Library.get().asFile
        val entryName = "jni/arm64-v8a/libonnxruntime.so"
        ZipFile(archiveFile).use { archive ->
            val entry = archive.getEntry(entryName)
                ?: error("ONNX Runtime 1.23.2 AAR does not contain $entryName")
            destination.parentFile.mkdirs()
            archive.getInputStream(entry).use { source ->
                destination.outputStream().use { output -> source.copyTo(output) }
            }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractFullOrtRuntime) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(extractFullOrtRuntime)
    doLast {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        val sourceRuntime = generatedOrtArm64Library.get().asFile
        val variantName = name.removePrefix("merge").removeSuffix("NativeLibs").replaceFirstChar { it.lowercase() }
        val mergedRuntime = layout.buildDirectory.get().asFile.resolve(
            "intermediates/merged_native_libs/$variantName/$name/out/lib/arm64-v8a/libonnxruntime.so",
        )
        check(sourceRuntime.isFile && sha256(sourceRuntime) == fullOrtArm64Sha256) {
            "The pinned full ONNX Runtime 1.23.2 ARM64 library is missing or has an unexpected SHA-256"
        }
        check(mergedRuntime.isFile && sha256(mergedRuntime) == fullOrtArm64Sha256) {
            "Variant '$variantName' did not merge the pinned full ONNX Runtime 1.23.2 ARM64 library"
        }
    }
}

android {
    namespace = "com.voztranslate.app"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.voztranslate.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The approved evaluation target is ARM64; the full ORT 1.23.2 runtime is shared by
        // Moonshine and M2M100. It replaces Moonshine's reduced ORT-format-only runtime.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                targets += "voztranslate_m2m100"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Both AARs contain this path. Keep the full upstream ORT package; every native
            // merge task verifies the merged library hash against the pinned dependency artifact.
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(
        layout.buildDirectory.get().asFile.resolve("generated/ort-runtime-jni"),
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

dependencies {
    add(fullOrtRuntimeAar.name, libs.onnxruntime.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.moonshine.voice)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
