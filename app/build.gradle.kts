import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
val ksProps = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "tw.timecam"
    compileSdk = 35
    defaultConfig { applicationId = "tw.timecam"; minSdk = 26; targetSdk = 35; versionCode = 10; versionName = "1.3.0" }
    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file("keystore/" + ksProps["storeFile"]); storePassword = ksProps["storePassword"] as String
            keyAlias = ksProps["keyAlias"] as String; keyPassword = ksProps["keyPassword"] as String
        }
    }
    buildTypes { release { isMinifyEnabled = false; if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    val cx = "1.4.0"
    implementation("androidx.camera:camera-core:$cx"); implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx"); implementation("androidx.camera:camera-view:$cx")
    implementation("androidx.camera:camera-video:$cx"); implementation("androidx.camera:camera-extensions:$cx")
    implementation("androidx.camera:camera-effects:$cx")
    implementation("androidx.core:core-ktx:1.13.1"); implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0"); implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    kapt("androidx.room:room-compiler:$room")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.room:room-testing:$room")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
