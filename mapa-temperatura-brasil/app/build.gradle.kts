plugins { id("com.android.application") }
android {
    namespace = "br.com.mapatemperaturabrasil"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.mapatemperaturabrasil"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "5.0 - Fronteiras detalhadas"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
