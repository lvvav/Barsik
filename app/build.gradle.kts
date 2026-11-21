plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.barsik.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.barsik.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        // Ключ подставляется при сборке из секрета OPENROUTER_API_KEY (в репозитории его нет)
        val key = System.getenv("OPENROUTER_API_KEY") ?: ""
        buildConfigField("String", "AI_KEY", "\"$key\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Офлайн распознавание речи для слова «Барсик»
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
