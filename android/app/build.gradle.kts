import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultRelayUrlProvider = providers
    .gradleProperty("ANDRODEX_DEFAULT_RELAY_URL")
    .orElse(providers.environmentVariable("ANDRODEX_DEFAULT_RELAY_URL"))
    .orElse("wss://relay.androdex.xyz/relay")
val fcmApplicationIdProvider = providers
    .gradleProperty("ANDRODEX_FCM_APPLICATION_ID")
    .orElse(providers.environmentVariable("ANDRODEX_FCM_APPLICATION_ID"))
    .orElse("")
val fcmProjectIdProvider = providers
    .gradleProperty("ANDRODEX_FCM_PROJECT_ID")
    .orElse(providers.environmentVariable("ANDRODEX_FCM_PROJECT_ID"))
    .orElse("")
val fcmApiKeyProvider = providers
    .gradleProperty("ANDRODEX_FCM_API_KEY")
    .orElse(providers.environmentVariable("ANDRODEX_FCM_API_KEY"))
    .orElse("")
val fcmSenderIdProvider = providers
    .gradleProperty("ANDRODEX_FCM_GCM_SENDER_ID")
    .orElse(providers.environmentVariable("ANDRODEX_FCM_GCM_SENDER_ID"))
    .orElse("")

fun escapeBuildConfigString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

fun resolveBuildValue(
    gradlePropertyName: String,
    environmentVariableName: String = gradlePropertyName,
    keystorePropertyName: String? = null
): String? {
    return providers.gradleProperty(gradlePropertyName).orNull
        ?: providers.environmentVariable(environmentVariableName).orNull
        ?: keystorePropertyName?.let(keystoreProperties::getProperty)
}

val releaseKeystorePath = resolveBuildValue(
    gradlePropertyName = "ANDRODEX_ANDROID_KEYSTORE_PATH",
    keystorePropertyName = "storeFile"
)
val releaseKeystorePassword = resolveBuildValue(
    gradlePropertyName = "ANDRODEX_ANDROID_KEYSTORE_PASSWORD",
    keystorePropertyName = "storePassword"
)
val releaseKeyAlias = resolveBuildValue(
    gradlePropertyName = "ANDRODEX_ANDROID_KEY_ALIAS",
    keystorePropertyName = "keyAlias"
)
val releaseKeyPassword = resolveBuildValue(
    gradlePropertyName = "ANDRODEX_ANDROID_KEY_PASSWORD",
    keystorePropertyName = "keyPassword"
)
val hasCompleteReleaseSigningConfig = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "io.androdex.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.androdex.android"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "0.2.1"
        buildConfigField(
            "String",
            "ANDRODEX_DEFAULT_RELAY_URL",
            "\"${escapeBuildConfigString(defaultRelayUrlProvider.get())}\""
        )
        buildConfigField(
            "String",
            "ANDRODEX_FCM_APPLICATION_ID",
            "\"${escapeBuildConfigString(fcmApplicationIdProvider.get())}\""
        )
        buildConfigField(
            "String",
            "ANDRODEX_FCM_PROJECT_ID",
            "\"${escapeBuildConfigString(fcmProjectIdProvider.get())}\""
        )
        buildConfigField(
            "String",
            "ANDRODEX_FCM_API_KEY",
            "\"${escapeBuildConfigString(fcmApiKeyProvider.get())}\""
        )
        buildConfigField(
            "String",
            "ANDRODEX_FCM_GCM_SENDER_ID",
            "\"${escapeBuildConfigString(fcmSenderIdProvider.get())}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasCompleteReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
    implementation("com.google.firebase:firebase-messaging:25.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
