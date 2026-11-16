plugins {
    alias(libs.plugins.camnode.android.library)
    alias(libs.plugins.camnode.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:control"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp.core)
}
