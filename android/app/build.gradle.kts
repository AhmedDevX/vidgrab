plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vidgrab.app"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.vidgrab.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Per-architecture APKs so phones download a small APK instead of the
    // universal one (the engine bundles Python + ffmpeg native code).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // youtubedl-android ships its native libs compressed (lib*.zip.so) and
    // needs them extracted at install time (with extractNativeLibs=true).
    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            // Bouncy Castle jars are signed and multi-release: drop the
            // colliding signature/metadata files (they are not needed at
            // runtime on Android, which never verifies JAR signatures).
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "META-INF/INDEX.LIST"
            )
            // All three Bouncy Castle jars carry the same license text;
            // keep one copy inside the APK instead of dropping them all.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Download engine: yt-dlp + ffmpeg packaged for Android (GPL-3.0).
    // JunkFood02's maintained fork on Maven Central (used by Seal and YTDLnis):
    // bundles Python 3.12 + ffmpeg 7.1.1 + QuickJS, so yt-dlp can stay current.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Embedded HTTP server for the file-sharing feature (BSD-3-Clause).
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QR code generation for the sharing link (Apache-2.0).
    implementation("com.google.zxing:core:3.5.4")

    // Self-signed device identity certificates for the direct-transfer
    // TLS server (Bouncy Castle License, MIT-style). javax.net.ssl handles
    // TLS itself; BC is only used to BUILD the X.509 certificate, which the
    // platform has no API for. Versions verified against repo1.maven.org.
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.json:json:20231013")
}
