import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * A JVM module that also runs inside the Android app. Lint can't check it against the
 * app's minSdk, so Animal Sniffer verifies its bytecode against the Android API
 * signature: a JDK-only call (for example a Java 10+ overload) fails the build instead of
 * crashing an old phone with NoSuchMethodError.
 */
class JvmAndroidCompatConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("camnode.jvm.library")
        pluginManager.apply("ru.vyarus.animalsniffer")
        dependencies {
            add("signature", libs.library("android-api-signature").map { "${it.module}:${it.versionConstraint.requiredVersion}@signature" })
        }
    }
}
