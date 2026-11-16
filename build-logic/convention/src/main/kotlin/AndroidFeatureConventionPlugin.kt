import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A feature owns one capability's UI and state. It depends on core modules only;
 * features never depend on each other and the app module composes them.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("camnode.android.library")
        pluginManager.apply("camnode.android.compose")
        pluginManager.apply("camnode.hilt")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:designsystem"))
            add("implementation", libs.library("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.library("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.library("kotlinx-coroutines-android"))
            add("implementation", libs.library("androidx-core-ktx"))
            add("implementation", libs.library("androidx-activity-compose"))
        }
    }
}
