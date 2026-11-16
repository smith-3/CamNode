plugins {
    alias(libs.plugins.camnode.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.rootencoder.library)
    implementation(project(":third_party:rtspserver"))
}
