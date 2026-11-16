plugins {
    alias(libs.plugins.camnode.android.feature)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore)
}
