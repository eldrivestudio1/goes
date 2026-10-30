plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}

android {
    namespace = "id.gowes.offline"
    compileSdk = 35

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
    defaultConfig {
        applicationId = "id.gowes.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
