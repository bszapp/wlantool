import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

val generatedJniDir = layout.buildDirectory.dir("generated/native-jni")

val releaseKeystorePath = providers.environmentVariable("WLANTOOL_RELEASE_KEYSTORE").orNull
val releaseStorePassword = providers.environmentVariable("WLANTOOL_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("WLANTOOL_RELEASE_KEY_ALIAS").orNull ?: "key0"
val releaseKeyPassword = providers.environmentVariable("WLANTOOL_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isBlank() &&
    !releaseKeyPassword.isNullOrBlank()

// Proot binaries are produced by a CMake add_custom_command (not add_library),
// so AGP 9.x does not automatically track them as native-build outputs.
// Register the staging task output through the Variant Sources API instead of
// passing a Provider to android.sourceSets, which AGP 9 rejects by default.

@DisableCachingByDefault(because = "Only stages generated native artifacts for APK packaging.")
abstract class StageProotLibsTask : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = inputDir.get().asFile
        val target = outputDir.get().asFile

        if (target.exists()) {
            target.deleteRecursively()
        }
        target.mkdirs()

        if (!source.isDirectory) {
            logger.warn("Proot JNI source directory does not exist yet: ${source.absolutePath}")
            return
        }

        source.walkTopDown()
            .filter { it.isFile && (it.name == "libproot.so" || it.name == "libproot-loader.so") }
            .forEach { file ->
                val destination = target.resolve(file.relativeTo(source))
                destination.parentFile.mkdirs()
                file.copyTo(destination, overwrite = true)
            }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.bszapp.wlantool"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.bszapp.wlantool"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DAPP_GENERATED_JNI_DIR=${generatedJniDir.get().asFile.absolutePath}"
            }
        }
    }

    androidResources {
        localeFilters += listOf("zh")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword!!
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // CI injects the release signing key from GitHub Secrets.
            // Local builds without those environment variables fall back to debug signing.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// ---------------------------------------------------------------------------
// Stage proot binaries (produced by CMake add_custom_command) into a dedicated
// per-variant directory so that AGP JNI-lib merging can track them as explicit
// generated JNI-library inputs.
// ---------------------------------------------------------------------------
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val stageProotLibs = tasks.register<StageProotLibsTask>("stage${variantName}ProotLibs") {
            description = "Copies libproot.so and libproot-loader.so from CMake output into " +
                    "the ${variant.name} proot JNI staging directory so AGP packages them in the APK."

            // Source: CMake writes both proot executables into OUTPUT_ABI_DIR which is
            //         APP_GENERATED_JNI_DIR/<abi>  =  generatedJniDir/<abi>.
            inputDir.set(generatedJniDir)
            outputDir.set(layout.buildDirectory.dir("generated/proot-jni/${variant.name}"))

            // CMake produces inputDir. Keep this dependency variant-specific so the
            // staging task cannot be validated/executed before externalNativeBuild*.
            dependsOn("externalNativeBuild${variantName}")

            // Native outputs can change underneath the staging directory; always
            // refresh this lightweight copy step when it participates in a build.
            outputs.upToDateWhen { false }
        }

        // Register the staging task output as generated JNI libraries. This avoids
        // the AGP 9 error caused by adding a Provider to android.sourceSets and
        // lets AGP carry the task dependency to JNI-lib consumers.
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            stageProotLibs,
            StageProotLibsTask::outputDir
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
}
