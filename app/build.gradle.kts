val generatedJniDir = layout.buildDirectory.dir("generated/native-jni").get().asFile

// Proot binaries are produced by a CMake add_custom_command (not add_library),
// so AGP 9.x does not automatically track them as native-build outputs.
// We stage them into a dedicated directory that AGP does track via jniLibs.srcDir.
val prootStagingDir = layout.buildDirectory.dir("generated/proot-jni")

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
                arguments += "-DAPP_GENERATED_JNI_DIR=${generatedJniDir.absolutePath}"
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

    sourceSets {
        getByName("main") {
            // Use the staging directory so AGP sees libproot.so and libproot-loader.so
            // as explicit JNI library inputs rather than relying on the shared cmake
            // output directory (which AGP partially tracks via add_library outputs).
            jniLibs.srcDir(prootStagingDir)
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
val stageProotLibs by tasks.registering(Copy::class) {
    description = "Copies libproot.so and libproot-loader.so from CMake output into " +
            "the proot JNI staging directory so AGP packages them in the APK."

    // Source: CMake writes both proot executables into OUTPUT_ABI_DIR which is
    //         APP_GENERATED_JNI_DIR/<abi>  =  generatedJniDir/<abi>.
    from(generatedJniDir) {
        include("**/libproot.so", "**/libproot-loader.so")
    }
    into(prootStagingDir)
}

// Wire task dependencies:
//   externalNativeBuild* (CMake builds libproot.so) → stageProotLibs → merge*NativeLibs
afterEvaluate {
    // stageProotLibs must run after all externalNativeBuild tasks
    tasks.matching { it.name.startsWith("externalNativeBuild") }.configureEach {
        stageProotLibs.get().dependsOn(this)
    }

    // JNI-lib merge tasks must run after stageProotLibs so they see the staged files
    tasks.matching { task ->
        val n = task.name
        (n.startsWith("merge") && n.endsWith("NativeLibs")) ||
        (n.startsWith("merge") && n.endsWith("JniLibFolders")) ||
        (n.startsWith("merge") && n.endsWith("JniLibs"))
    }.configureEach {
        dependsOn(stageProotLibs)
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
