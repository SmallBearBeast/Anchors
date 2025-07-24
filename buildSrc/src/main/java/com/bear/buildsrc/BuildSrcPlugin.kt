package com.bear.buildsrc

import org.gradle.api.Plugin
import org.gradle.api.Project

class BuildSrcPlugin: Plugin<Project>{
    override fun apply(project: Project) {
        println("Hello, I am a BuildSrcPlugin")

        //自定义 Task
        val buildSrcTask = project.task("BuildSrcTask") {
            it.doLast {
                println("Hello, I am a buildSrcTask")
            }
        }

        //挂接到 mergeDebugResources 后面执行
        project.afterEvaluate {
            //获取 Task 时，需在当前 build.gradle 文件执行之后，否则获取的 Task 会为 null
            //获取构建流程中的 Task
            val mergeDebugResourcesTask = project.tasks.findByName("mergeDebugResources")
            println("mergeDebugResourcesTask is ${if (mergeDebugResourcesTask != null) "not null" else "null"}")
            mergeDebugResourcesTask?.finalizedBy(buildSrcTask)
//                buildSrcTask?.dependsOn(mergeDebugResourcesTask)
        }
    }
}