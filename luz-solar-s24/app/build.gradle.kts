plugins { id("com.android.application") }
android {
    namespace = "com.wes1999.luzsolars24"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wes1999.luzsolars24"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
