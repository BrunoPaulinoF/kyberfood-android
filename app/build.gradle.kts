plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Endereços e chave PÚBLICA (anon) do Supabase entram pelo AMBIENTE do build, nunca pelo
// repositório: a mesma regra do app desktop. Sem eles o app compila, mas a tela de login
// avisa que o build está sem configuração.
fun cfg(name: String, fallback: String = ""): String =
    (System.getenv(name) ?: (findProperty(name) as String?) ?: fallback).trim()

android {
    namespace = "br.com.kyberfood.impressora"
    compileSdk = 35

    defaultConfig {
        // NUNCA mude: é a identidade do app no celular. Trocar vira OUTRO app (o instalado
        // não atualiza e o login guardado fica para trás).
        applicationId = "br.com.kyberfood.impressora"
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("KYBERFOOD_VERSION_CODE", "1").toInt()
        versionName = cfg("KYBERFOOD_VERSION_NAME", "0.3.0")

        buildConfigField("String", "SUPABASE_URL", "\"${cfg("KYBERFOOD_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("KYBERFOOD_SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "API_URL", "\"${cfg("KYBERFOOD_API_URL", "https://kyberfood.com.br")}\"")
    }

    signingConfigs {
        // A chave definitiva vem do ambiente (secrets do build). Sem ela, o build usa a
        // chave de teste do Android — serve para a prova, não para publicar.
        val keystorePath = cfg("KYBERFOOD_KEYSTORE_FILE")
        if (keystorePath.isNotEmpty()) {
            create("kyberfood") {
                storeFile = file(keystorePath)
                storePassword = cfg("KYBERFOOD_KEYSTORE_PASSWORD")
                keyAlias = cfg("KYBERFOOD_KEY_ALIAS")
                keyPassword = cfg("KYBERFOOD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("kyberfood") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
