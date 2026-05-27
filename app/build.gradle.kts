import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

val generatedJniDir = layout.buildDirectory.dir("generated/native-jni")

// Proot binaries are produced by a CMake add_custom_command (not add_library),
// so AGP 9.x does not automatically track them as native-build outputs.
// Register the staging task output through the Variant Sources API instead of
// passing a Provider to android.sourceSets, which AGP 9 rejects by default.
val prootStagingDir = layout.buildDirectory.dir("generated/proot-jni")

abstract class StageProotLibsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        project.copy {
            from(inputDir) {
                include("**/libproot.so", "**/libproot-loader.so")
            }
            into(outputDir)
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
        resourceConfigurations += listOf("zh")

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DAPP_GENERATED_JNI_DIR=${generatedJniDir.get().asFile.absolutePath}"
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // GitHub Actions does not run a separate signing step.
            // The release APK is signed here with Android's default debug keystore.
            signingConfig = signingConfigs.getByName("debug")
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
// directory so that AGP JNI-lib merging can track them as explicit inputs.
// ---------------------------------------------------------------------------
val stageProotLibs by tasks.registering(StageProotLibsTask::class) {
    description = "Copies libproot.so and libproot-loader.so from CMake output into " +
            "the proot JNI staging directory so AGP packages them in the APK."

    // Source: CMake writes both proot executables into OUTPUT_ABI_DIR which is
    //         APP_GENERATED_JNI_DIR/<abi>  =  generatedJniDir/<abi>.
    inputDir.set(generatedJniDir)
    outputDir.set(prootStagingDir)
}

androidComponents {
    onVariants { variant ->
        // Register the staging task output as generated JNI libraries. This avoids
        // the AGP 9 error caused by adding a Provider to android.sourceSets and
        // lets AGP carry the task dependency to JNI-lib consumers.
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            stageProotLibs,
            StageProotLibsTask::outputDir
        )
    }
}

// Wire task dependencies:
//   externalNativeBuild* (CMake builds libproot.so) → stageProotLibs.
//   stageProotLibs → JNI-lib consumers is wired by addGeneratedSourceDirectory.
afterEvaluate {
    // stageProotLibs must run after all externalNativeBuild tasks
    tasks.matching { it.name.startsWith("externalNativeBuild") }.configureEach {
        stageProotLibs.get().dependsOn(this)
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
