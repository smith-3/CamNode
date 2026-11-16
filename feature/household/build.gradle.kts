plugins {
    alias(libs.plugins.camnode.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
