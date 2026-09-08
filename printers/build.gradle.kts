plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x compiles Compose through this plugin.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "zw.co.unipay.printers"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures { compose = true }
}

// jvmTarget moved out of android.kotlinOptions in Kotlin 2.x; setting it there is an error
// rather than a warning inside a host build on a newer Kotlin.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // api rather than implementation: the setup screen is a Composable in this library's own
    // signature, so an application cannot host it without Compose on its compile classpath.
    api(platform("androidx.compose:compose-bom:2024.10.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    testImplementation("junit:junit:4.13.2")
}
