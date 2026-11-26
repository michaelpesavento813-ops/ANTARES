plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.antares.prototype"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.antares.prototype"
        minSdk = 26
        testInstrumentationRunner = "com.antares.prototype.AcceptanceRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:1.2.3")
    testImplementation("junit:junit:4.13.2")
}

val verifyModel by tasks.registering(Exec::class) {
    commandLine("bash", rootProject.file("ci/fetch-model.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(verifyModel) }
val verifyTestImage by tasks.registering(Exec::class) {
    commandLine("bash", rootProject.file("ci/fetch-test-image.sh").absolutePath)
}
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(verifyTestImage) }
