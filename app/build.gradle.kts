import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing is read from keystore.properties at the repo root, which is
// gitignored and must never be committed — it holds the key passwords. When it
// is absent the release build still assembles, just unsigned, so CI and anyone
// without the key can still verify that a release configuration compiles.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.slurp"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.slurp"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"

        // The Python runtime is per-ABI. Anything not listed here is not
        // shipped, so the app simply will not run on that architecture.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // A universal APK carries three copies of the Python runtime and lands
    // around 180 MB. The per-ABI splits are roughly a third of that; install
    // the arm64-v8a one on any phone made in the last several years.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Falls back to this machine's debug key when there is no
            // keystore.properties, which is how nyaarank ships and is the
            // reason releases can be cut without a manual signing step.
            //
            // The consequence is the same one nyaarank documents: Android only
            // accepts an update signed with the same key as the install, so
            // releases have to keep coming from this machine. Building
            // elsewhere means uninstall-then-reinstall. Drop a
            // keystore.properties in the repo root to switch to a real key —
            // that also forces one uninstall, since the key changes.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // Deliberately off. The library reaches into the bundled Python
            // by name and R8 has no way to see those references, so a
            // minified build dies at runtime rather than at compile time.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Required. This sets android:extractNativeLibs=true, which makes the
        // installer write the .so files out to the filesystem — the bundled
        // Python runtime needs real files and cannot be read from inside the
        // APK. It does NOT mean "store the libraries uncompressed"; they are
        // DEFLATE-compressed in the APK either way, and false is the setting
        // that stops them being extracted at all. See the README.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)
}
