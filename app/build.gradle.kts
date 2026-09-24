import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.li63050a.linuxandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.li63050a.linuxandroid"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // 读取本地 local.properties（用于本地测试时读取签名配置）
    val localProps = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localProps.load(localFile.inputStream())
    }

    signingConfigs {
        create("release") {
            // 优先级：环境变量(GitHub Actions注入) > local.properties(本地测试) > 兜底
            val storeFilePath = System.getenv("KEYSTORE_PATH")
                ?: localProps.getProperty("KEYSTORE_PATH")
                ?: "myapp.jks"
            storeFile = file(storeFilePath)

            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: localProps.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS")
                ?: localProps.getProperty("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: localProps.getProperty("KEY_PASSWORD") ?: ""

            // 签名方案：V2/V3（minSdk 24 起设备均支持），关闭 V1 与 V4
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    packaging {
        jniLibs {
            useLegacyPackaging = true // 保证 libproot.so 能解压并执行
        }
    }
}

dependencies {
    // UI：AppCompat + Material + DrawerLayout + RecyclerView（现代化浅色界面）
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("com.google.android.material:material:1.12.0")
    // 核心功能
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
