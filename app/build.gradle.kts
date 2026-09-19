plugins {
    id("com.android.application")
}

android {
    namespace = "io.giasinton.countday"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.giasinton.countday"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    // 不依赖 AndroidX / 任何第三方库：纯 framework，所以没有 dependencies 块。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
