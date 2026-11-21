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
        // Номер сборки GitHub Actions: по нему приложение понимает, что вышла новая версия
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "2").toInt()
        versionName = "2.0"

        // Только современные телефоны (arm64): APK заметно легче
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Ключ подставляется при сборке из секрета OPENROUTER_API_KEY (в репозитории его нет)
        val key = System.getenv("OPENROUTER_API_KEY") ?: ""
        buildConfigField("String", "AI_KEY", "\"$key\"")
        // Встроенная нейросеть: Cloudflare Workers AI. Ключ — токен Cloudflare из секрета OPENROUTER_API_KEY
        buildConfigField("String", "AI_URL", "\"https://api.cloudflare.com/client/v4/accounts/766c7c1985b628a23b73cb672143711a/ai/v1/chat/completions\"")
        buildConfigField("String", "AI_MODEL", "\"@cf/meta/llama-3.3-70b-instruct-fp8-fast\"")
    }

    // Постоянный ключ подписи лежит в репозитории (app/signing): все сборки подписаны одним ключом,
    // поэтому обновление ставится поверх уже установленного приложения
    signingConfigs {
        create("ci") {
            storeFile = file("signing/barsik.jks")
            storePassword = "4ae0a2054189a8d79ccf29697ac94b63"
            keyAlias = "barsik"
            keyPassword = "4ae0a2054189a8d79ccf29697ac94b63"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ci")
        }
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
