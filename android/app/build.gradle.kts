import java.util.Properties

plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }

// Adapted from Flocket's release signing setup (Apache-2.0). See docs/RELEASING.md.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(property: String, name: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(name) ?: providers.gradleProperty(name).orNull)
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "TREFOIL_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "TREFOIL_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "TREFOIL_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "TREFOIL_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "com.tajchert.trefoil"
    compileSdk = 37
    defaultConfig { applicationId = "com.tajchert.trefoil"; minSdk = 29; targetSdk = 37; versionCode = 4; versionName = "0.3.0" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}
dependencies {
    implementation(project(":core:model")); implementation(project(":core:session")); implementation(project(":core:ssh"))
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
