package io.github.aftrolle.ffmpegkmp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class PublicationScopeVerificationTask : DefaultTask() {
    @get:Input
    abstract val expectedPublishingProjects: SetProperty<String>

    @get:Input
    abstract val publishingProjects: SetProperty<String>

    @TaskAction
    fun verifyPublicationScope() {
        val expected = expectedPublishingProjects.get()
        val actual = publishingProjects.get()
        check(actual == expected) {
            "Unexpected Maven publication scope. Expected $expected but found $actual"
        }
    }
}
