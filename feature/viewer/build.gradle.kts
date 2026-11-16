plugins {
    alias(libs.plugins.camnode.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui.compose)
}
