// The match referee the game server runs: the game's own simulation (sim/, ai/, data/ from the app, which are
// pure Kotlin) plus a small command-line front end. `./gradlew :referee:installReferee` builds it and puts it
// in server/referee/referee.jar. Rebuild it whenever anything under sim/, ai/ or data/ changes: the server must
// run exactly the simulation the game does.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin {
        srcDir("src/main/kotlin")
        srcDir("../app/src/main/java")
        include("io/github/projectwip/sim/**", "io/github/projectwip/ai/**", "io/github/projectwip/data/**", "io/github/projectwip/referee/**")
        // The two files in data/ that need Android.
        exclude("io/github/projectwip/data/SaveStore.kt", "io/github/projectwip/data/GameRepository.kt")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.jar {
    archiveFileName.set("referee.jar")
    manifest { attributes["Main-Class"] = "io.github.projectwip.referee.MainKt" }
    // One self-contained file: the Kotlin runtime goes inside.
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("installReferee") {
    from(tasks.jar)
    into(rootProject.file("../server/referee"))
}
