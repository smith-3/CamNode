plugins {
    alias(libs.plugins.camnode.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

/** Desktop node that stores what the household cameras see. Runs anywhere with a JVM and ffmpeg. */
application {
    applicationName = "camnode-recorder"
    mainClass.set("com.fragmind.camnode.recorder.MainKt")
}

dependencies {
    implementation(project(":core:control"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
