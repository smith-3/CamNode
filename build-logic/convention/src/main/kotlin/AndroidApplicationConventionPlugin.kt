import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = CamNodeSdk.COMPILE
            defaultConfig {
                minSdk = CamNodeSdk.MIN
                targetSdk = CamNodeSdk.TARGET
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = CamNodeSdk.JAVA
                targetCompatibility = CamNodeSdk.JAVA
            }
            testOptions.unitTests.isReturnDefaultValues = true
        }
    }
}
