plugins {
    alias(libs.plugins.camnode.android.library)
    alias(libs.plugins.camnode.android.compose)
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
}

// BrandColorsTest reads this file directly, so changes to it must rerun the tests.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/res/values/brand_colors.xml")
}
