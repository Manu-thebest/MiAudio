import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cat.manu.miaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "cat.manu.miaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "2.10"
    }

    // La firma se toma de local.properties (no versionado) para no publicar
    // contraseñas ni el keystore. Sin esas propiedades el release sale sin firmar.
    val firma = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val clave = firma.getProperty("miaudio.storePassword") ?: ""
    val ficheroClave = firma.getProperty("miaudio.storeFile") ?: "keystore/miaudio.jks"
    val ks = rootProject.file(ficheroClave)
    val hayFirma = clave.isNotEmpty() && ks.exists()

    signingConfigs {
        if (hayFirma) create("propia") {
            storeFile = ks
            storePassword = clave
            keyAlias = firma.getProperty("miaudio.keyAlias") ?: "miaudio"
            keyPassword = firma.getProperty("miaudio.keyPassword") ?: clave
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hayFirma) signingConfig = signingConfigs.getByName("propia")
        }
        debug {
            if (hayFirma) signingConfig = signingConfigs.getByName("propia")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.google.guava:guava:33.3.1-android")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("org.jsoup:jsoup:1.24.1")
    implementation("io.coil-kt:coil:2.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
