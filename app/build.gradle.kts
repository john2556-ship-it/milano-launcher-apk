plugins {
    id("com.android.application")
}

// Số bản do GitHub Actions truyền vào (-PversionCode=...), máy thường thì là 1
val soBan = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
// Địa chỉ trang launcher: sửa trong file launcher-url.txt ở thư mục gốc
val launcherUrl = rootProject.file("launcher-url.txt").readText().trim()

android {
    namespace = "com.milano.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.milano.launcher"
        minSdk = 23
        targetSdk = 35
        versionCode = soBan
        versionName = "1.0.$soBan"
        buildConfigField("String", "LAUNCHER_URL", "\"$launcherUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/milano-release.p12")
            storeType = "PKCS12"
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = "milano"
            keyPassword = System.getenv("KEYSTORE_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
