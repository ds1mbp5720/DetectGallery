plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "lee.project.detectgallery"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "lee.project.detectgallery"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    listOf(":core:domain", ":core:imaging", ":core:data", ":core:ml", ":core:ui",
        ":feature:live", ":feature:search", ":feature:people").forEach { implementation(project(it)) }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime)
    implementation(libs.hilt.navigation.compose)       // AppNavHost 의 hiltViewModel()
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)

}