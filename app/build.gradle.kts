plugins {
    alias(libs.plugins.camnode.android.application)
    alias(libs.plugins.camnode.android.compose)
    alias(libs.plugins.camnode.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fragmind.camnode"

    defaultConfig {
        applicationId = "com.fragmind.camnode"
        versionCode = 1
        versionName = "0.1.0"
    }

    // The in-app language picker needs every translation on every phone; Play's per-language
    // splits would only ship the languages set in the phone's settings.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":feature:household"))
    implementation(project(":feature:alerts"))
    implementation(project(":feature:applock"))
    implementation(project(":feature:node"))
    implementation(project(":feature:viewer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
