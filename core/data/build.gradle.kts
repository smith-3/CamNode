plugins {
    alias(libs.plugins.camnode.android.library)
    alias(libs.plugins.camnode.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:control"))
    implementation(project(":core:network"))
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
}
