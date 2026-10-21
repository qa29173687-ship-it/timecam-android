import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val ksProps = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "tw.timecam"
    compileSdk = 35
    defaultConfig { applicationId = "tw.timecam"; minSdk = 26; targetSdk = 35; versionCode = 7; versionName = "1.1.4" }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
