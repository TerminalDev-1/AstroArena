plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.projectwip"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.projectwip"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "9"
        // Where the game looks for its server unless the player sets another address in Settings.
        // Override at build time with -Pastro.server=http://host:port
        buildConfigField("String", "SERVER_URL", "\"${(project.findProperty("astro.server") as String?) ?: "http://192.168.1.103:8765"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Preview builds are signed with the debug key so they can be sideloaded.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
}
