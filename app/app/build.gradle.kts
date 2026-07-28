import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kindredcall.app"
    compileSdk = 35

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { stream ->
            localProperties.load(stream)
        }
    }

    val serverIp = localProperties.getProperty("SERVER_IP") ?: "YOUR_SERVER_IP"
    val turnDomain = localProperties.getProperty("TURN_DOMAIN") ?: "YOUR_TURN_DOMAIN"
    val turnUser = localProperties.getProperty("TURN_USER") ?: "your_username"
    val turnPass = localProperties.getProperty("TURN_PASS") ?: "your_password"

    defaultConfig {
        applicationId = "com.kindredcall.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SIGNALING_URL", "\"ws://$serverIp:8080\"")
        buildConfigField("String", "API_BASE_URL", "\"http://$serverIp:3000\"")
        buildConfigField("String", "TURN_URL", "\"turns:$turnDomain:443?transport=tcp\"")
        buildConfigField("String", "TURN_USER", "\"$turnUser\"")
        buildConfigField("String", "TURN_PASS", "\"$turnPass\"")

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