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
        versionCode = 6
        versionName = "0.6.0-dev"
    }
    val keystore = providers.environmentVariable("ANTARES_SIGNING_KEYSTORE").orNull
    if (keystore != null) {
        val production = signingConfigs.create("production") {
            storeFile = rootProject.file(keystore)
            storePassword = providers.environmentVariable("ANTARES_SIGNING_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANTARES_SIGNING_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANTARES_SIGNING_KEY_PASSWORD").get()
        }
        buildTypes.named("release") { signingConfig = production }
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

val verifySegmenter by tasks.registering(Exec::class) {
    commandLine("bash", rootProject.file("ci/fetch-segmenter.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(verifySegmenter) }
