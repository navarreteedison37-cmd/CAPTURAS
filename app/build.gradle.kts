plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.capturadatos.ocr"; compileSdk = 35
    defaultConfig { applicationId = "com.capturadatos.ocr"; minSdk = 23; targetSdk = 35; versionCode = 5; versionName = "5.0.0" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
