import java.util.zip.ZipFile
import java.security.MessageDigest

fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// The full ORT AAR is used as a source artifact only. Its runtime replaces the minimal
// ONNX-format-incompatible library embedded in Moonshine for every supported ABI.
val fullOrtRuntimeAar = configurations.create("fullOrtRuntimeAar") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val generatedOrtJniDirectory = layout.buildDirectory.dir("generated/ort-runtime-jni")
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val fullOrtRuntimeSha256 = mapOf(
    "arm64-v8a" to "e40f09d07dc53726b8bfbf48a7907673b8f86718a057655a62790a39874a7302",
    "armeabi-v7a" to "57048b8d54896d16355ee367bfc129c5925468ae503b681b8d0cd49ceefa468e",
    "x86_64" to "972c17c056eaae946a415d9efdd8018b729639974df075e495f0092441478fb7",
)
val generatedOrtRuntimeLibraries = supportedAbis.associateWith { abi ->
    generatedOrtJniDirectory.map { it.file("$abi/libonnxruntime.so") }
}
val extractFullOrtRuntime = tasks.register("extractFullOrtRuntime") {
    inputs.files(fullOrtRuntimeAar)
    outputs.dir(generatedOrtJniDirectory)
    doLast {
        val archiveFile = fullOrtRuntimeAar.singleFile
        ZipFile(archiveFile).use { archive ->
            supportedAbis.forEach { abi ->
                val entryName = "jni/$abi/libonnxruntime.so"
                val entry = archive.getEntry(entryName)
                    ?: error("ONNX Runtime 1.23.2 AAR does not contain $entryName")
                val destination = generatedOrtRuntimeLibraries.getValue(abi).get().asFile
                destination.parentFile.mkdirs()
                archive.getInputStream(entry).use { source ->
                    destination.outputStream().use { output -> source.copyTo(output) }
                }
                val expectedSha256 = fullOrtRuntimeSha256.getValue(abi)
                val actualSha256 = sha256(destination)
                check(actualSha256 == expectedSha256) {
                    "The original ONNX Runtime 1.23.2 $abi library has unexpected SHA-256 " +
                        "$actualSha256 (expected $expectedSha256)"
                }
            }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractFullOrtRuntime) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(extractFullOrtRuntime)
    doLast {
        val variantName = name.removePrefix("merge").removeSuffix("NativeLibs").replaceFirstChar { it.lowercase() }
        val mergedLibrariesDirectory = layout.buildDirectory.get().asFile.resolve(
            "intermediates/merged_native_libs/$variantName/$name/out/lib",
        )
        supportedAbis.forEach { abi ->
            val expectedSha256 = fullOrtRuntimeSha256.getValue(abi)
            val sourceRuntime = generatedOrtRuntimeLibraries.getValue(abi).get().asFile
            val sourceSha256 = sha256(sourceRuntime)
            check(sourceSha256 == expectedSha256) {
                "The pinned full ONNX Runtime 1.23.2 $abi library is missing or has unexpected SHA-256 " +
                    "$sourceSha256 (expected $expectedSha256)"
            }

            val mergedRuntime = mergedLibrariesDirectory.resolve("$abi/libonnxruntime.so")
            mergedRuntime.parentFile.mkdirs()
            // AGP needs a duplicate-file rule for the AAR collision, but its selected file is not
            // trusted. Replace it with the library extracted above before packaging the variant.
            sourceRuntime.copyTo(mergedRuntime, overwrite = true)
            val mergedSha256 = sha256(mergedRuntime)
            check(mergedSha256 == expectedSha256) {
                "Variant '$variantName' did not package the pinned full ONNX Runtime 1.23.2 $abi " +
                    "library (got $mergedSha256; expected $expectedSha256)"
            }
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

        // Build JNI/SentencePiece and package full ONNX Runtime for each supported device ABI.
        ndk {
            supportedAbis.forEach { abiFilters += it }
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
            // Resolve the AAR collision so AGP can merge; the merge task then replaces this
            // path with our extracted full runtime and verifies its hash for every supported ABI.
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(generatedOrtJniDirectory.get().asFile)

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
