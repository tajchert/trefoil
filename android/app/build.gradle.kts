plugins { alias(libs.plugins.android.application); alias(libs.plugins.compose.compiler) }
android {
    namespace = "com.tajchert.trefoil"
    compileSdk = 37
    defaultConfig { applicationId = "com.tajchert.trefoil"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
dependencies {
    implementation(project(":core:model")); implementation(project(":core:session"))
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
