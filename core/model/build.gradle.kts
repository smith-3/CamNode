plugins {
    alias(libs.plugins.camnode.jvm.androidcompat)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
