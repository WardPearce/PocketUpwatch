plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    namespace = "com.pocketupwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.wardpearce.pocketupwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.0"
    }
    signingConfigs {
        create("release") {
            val keystore = System.getenv("ANDROID_KEYSTORE_PATH")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (System.getenv("ANDROID_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { name ->
                System.getProperty(name)?.let { value -> it.systemProperty(name, value) }
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("com.patrykandpatrick.vico:compose-m3:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
