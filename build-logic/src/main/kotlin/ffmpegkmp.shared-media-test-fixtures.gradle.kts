import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Makes the repository's shared media samples available to multiplatform tests.
 *
 * Modules opt in explicitly so the base library convention does not need to
 * infer behavior from project names or paths.
 */
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonTest") {
            resources.srcDir(
                rootProject.layout.projectDirectory.dir("library/core/src/commonTest/resources"),
            )
        }
    }
}
