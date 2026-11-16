plugins {
    alias(libs.plugins.camnode.android.library)
}

/**
 * Vendored pedroSG94/RTSP-Server 1.4.3 (Apache 2.0, see LICENSE) with per-connection
 * authorization and per-viewer credentials. Kept as close to upstream as possible.
 */
android {
    namespace = "com.pedro.rtspserver"
    lint {
        // Upstream code: report, but do not hold CamNode's build to its style.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    api(libs.rootencoder.library)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.network)
    implementation(libs.ktor.network.tls)
}
