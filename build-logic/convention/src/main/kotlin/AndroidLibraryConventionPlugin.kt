import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = namespaceFromPath()
            compileSdk = CamNodeSdk.COMPILE
            defaultConfig {
                minSdk = CamNodeSdk.MIN
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = CamNodeSdk.JAVA
                targetCompatibility = CamNodeSdk.JAVA
            }
            testOptions.unitTests.isReturnDefaultValues = true
        }
        dependencies {
            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("kotlinx-coroutines-test"))
        }
    }
}
