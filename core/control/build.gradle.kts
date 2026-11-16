plugins {
    alias(libs.plugins.camnode.jvm.androidcompat)
    alias(libs.plugins.kotlin.serialization)
}

/** The phone-to-phone contract, shared by the Android app and the desktop recorder. */
dependencies {
    api(project(":core:model"))
    api(libs.okhttp.core)
    api(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
