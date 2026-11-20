plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val iqonliKeystorePath = System.getenv("IQONLI_KEYSTORE_PATH")
val iqonliStorePassword = System.getenv("IQONLI_STORE_PASSWORD")
val iqonliKeyPassword = System.getenv("IQONLI_KEY_PASSWORD")
val iqonliKeyAlias = System.getenv("IQONLI_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "iqonli-1"
val signingValues = listOf(iqonliKeystorePath, iqonliStorePassword, iqonliKeyPassword)
val hasAnySigningValue = signingValues.any { !it.isNullOrBlank() }
val hasCompleteSigningValues = signingValues.all { !it.isNullOrBlank() }

if (hasAnySigningValue && !hasCompleteSigningValues) {
    throw GradleException(
        "Incomplete iqonli-1 signing configuration. Set IQONLI_KEYSTORE_PATH, " +
            "IQONLI_STORE_PASSWORD and IQONLI_KEY_PASSWORD together."
    )
}

android {
    namespace = "com.iqonli.ezxlrc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iqonli.ezxlrc"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
    }

    if (hasCompleteSigningValues) {
        val keyFile = file(iqonliKeystorePath!!)
        if (!keyFile.isFile) {
            throw GradleException("iqonli-1 keystore does not exist: $keyFile")
        }
        signingConfigs.create("iqonli") {
            storeFile = keyFile
            storePassword = iqonliStorePassword
            keyAlias = iqonliKeyAlias
            keyPassword = iqonliKeyPassword
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // Keep release bytecode unshrunk while diagnosing the device startup crash.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasCompleteSigningValues) {
                signingConfig = signingConfigs.getByName("iqonli")
            }
        }
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation("junit:junit:4.13.2")
}
