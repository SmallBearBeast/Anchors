package com.bear.annotation

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class TaskConfig(
    val name: String = "",
    val dependencies: Array<String> = [],
    val priority: Int = 0,
    val description: String = "",
)