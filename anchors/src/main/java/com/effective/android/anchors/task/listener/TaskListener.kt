package com.effective.android.anchors.task.listener

import com.effective.android.anchors.task.Task

private typealias OnStart = (task: Task) -> Unit
private typealias OnRunning = (task: Task) -> Unit
private typealias OnFinish = (task: Task) -> Unit
private typealias OnRelease = (task: Task) -> Unit

interface TaskListener {
    fun onStart(task: Task)
    fun onRunning(task: Task)
    fun onFinish(task: Task)
    fun onRelease(task: Task)
}

class TaskListenerBuilder : TaskListener {

    private var onStart: OnStart? = null
    private var onRunning: OnRunning? = null
    private var onFinish: OnFinish? = null
    private var onRelease: OnRelease? = null

    override fun onStart(task: Task) {
        onStart?.invoke(task)
    }

    override fun onRunning(task: Task) {
        onRunning?.invoke(task)
    }

    override fun onFinish(task: Task) {
        onFinish?.invoke(task)
    }

    override fun onRelease(task: Task) {
        onRelease?.invoke(task)
    }

    fun onStart(onStart: OnStart) {
        this.onStart = onStart
    }

    fun onRunning(onRunning: OnRunning) {
        this.onRunning = onRunning
    }

    fun onFinish(onFinish: OnFinish) {
        this.onFinish = onFinish
    }

    fun onRelease(onRelease: OnRelease) {
        this.onRelease = onRelease
    }
}