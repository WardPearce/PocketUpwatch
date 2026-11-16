plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt"); id("org.jetbrains.kotlin.plugin.compose") }
// Tests use API 28 plus API 35 for resource loading. Resolve both through Gradle,
// rather than making Robolectric download it (and checksums) during every test run.
val robolectricSdk by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val robolectricResourcesSdk by configurations.creating { isCanBeConsumed = false; isTransitive = false }
val prepareRobolectricSdk = tasks.register<Sync>("prepareRobolectricSdk") {
    from(robolectricSdk, robolectricResourcesSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
android {
    namespace = "com.pocketupwatch"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.wardpearce.pocketupwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.1.13"
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
            it.dependsOn(prepareRobolectricSdk)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort").forEach { name ->
                System.getProperty(name)?.let { value -> it.systemProperty(name, value) }
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    add(robolectricSdk.name, "org.robolectric:android-all-instrumented:9-robolectric-4913185-2-i7")
    add(robolectricResourcesSdk.name, "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("io.github.rosemoe:editor:0.23.7")
    implementation("io.github.rosemoe:language-textmate:0.23.7")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
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
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
