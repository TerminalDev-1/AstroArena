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
        // Players see "Beta". The build number ("95", or "94.1" for a point release) is what the updater and the
        // server's version gate compare; Android's own versionCode is made from it (94.1 -> 9401, 95 -> 9500).
        val build = "98"
        val (major, minor) = build.split(".").let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: 0) }
        versionCode = major * 100 + minor
        buildConfigField("String", "BUILD", "\"$build\"")
        versionName = "Beta"
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

    // The server's News, as it was when the game was built: shown when the server can't be reached.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/newsAssets"))
}

val copyNews by tasks.registering(Copy::class) {
    from(rootProject.file("../server/news.cfg"))
    into(layout.buildDirectory.dir("generated/newsAssets"))
}
tasks.named("preBuild") { dependsOn(copyNews) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)

    testImplementation(libs.junit)
    // The real JSON library, for tests that run the 1v1 link (the Android one is only a stub off the device).
    testImplementation("org.json:json:20240303")
}
