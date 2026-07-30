import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { stream ->
        localProperties.load(stream)
    }
}

val serverHosts = localProperties.getProperty("SERVER_HOSTS") ?: "app.example.com"
val turnHosts = localProperties.getProperty("TURN_HOSTS") ?: "turn.example.com"
val turnUser = localProperties.getProperty("TURN_USER") ?: "user"
val turnPass = localProperties.getProperty("TURN_PASS") ?: "changeme"
val sharedToken = localProperties.getProperty("SHARED_TOKEN") ?: "changeme"

val primaryHost = serverHosts.split(",").first().trim()
val signalingUrls = serverHosts.split(",").joinToString(",") { "wss://${it.trim()}/ws" }

android {
    namespace = "com.kindredcall.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kindredcall.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "SIGNALING_URLS", "\"$signalingUrls\"")
        buildConfigField("String", "API_BASE_URL", "\"https://$primaryHost\"")
        buildConfigField("String", "TURN_HOSTS", "\"$turnHosts\"")
        buildConfigField("String", "TURN_USER", "\"$turnUser\"")
        buildConfigField("String", "TURN_PASS", "\"$turnPass\"")
        buildConfigField("String", "SHARED_TOKEN", "\"$sharedToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    flavorDimensions += "user"
    productFlavors {
        create("grandma") {
            dimension = "user"
            applicationIdSuffix = ".grandma"
            buildConfigField("String", "USER_TYPE", "\"GRANDMA\"")
            isDefault = true
        }
        create("yulia") {
            dimension = "user"
            applicationIdSuffix = ".yulia"
            buildConfigField("String", "USER_TYPE", "\"YULIA\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.gson)
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    implementation("io.getstream:stream-webrtc-android-ui:1.1.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("assembleDebugUnitTest") {
    dependsOn("assembleGrandmaDebugUnitTest", "assembleYuliaDebugUnitTest")
}

tasks.register("assembleReleaseUnitTest") {
    dependsOn("assembleGrandmaReleaseUnitTest", "assembleYuliaReleaseUnitTest")
}

tasks.register("assembleDebugAndroidTest") {
    dependsOn("assembleGrandmaDebugAndroidTest", "assembleYuliaDebugAndroidTest")
}

tasks.register("assembleReleaseAndroidTest") {
    dependsOn("assembleGrandmaReleaseAndroidTest", "assembleYuliaReleaseAndroidTest")
}
