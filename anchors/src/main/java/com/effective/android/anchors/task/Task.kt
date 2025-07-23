package com.effective.android.anchors.task

import android.text.TextUtils
import com.effective.android.anchors.AnchorsRuntime
import com.effective.android.anchors.log.LogTaskListener
import com.effective.android.anchors.task.listener.TaskListener
import com.effective.android.anchors.task.listener.TaskListenerBuilder
import com.effective.android.anchors.task.lock.LockableTask
import com.effective.android.anchors.task.project.Project
import com.effective.android.anchors.util.Utils.compareTask
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

abstract class Task @JvmOverloads constructor(
    // id,唯一存在
    val id: String,
    // 是否是异步存在
    val isAsyncTask: Boolean = false
) : Runnable, Comparable<Task> {

    companion object {
        const val DEFAULT_PRIORITY = 0
    }

    @TaskState
    var state = TaskState.IDLE
        protected set

    // 优先级，数值越低，优先级越低
    var priority = DEFAULT_PRIORITY

    var executeTime: Long = 0
        protected set

    // 被依赖者
    val behindTasks = CopyOnWriteArrayList<Task>()

    // 依赖者
    val dependTasks = CopyOnWriteArraySet<Task>()

    // 监听器
    private val taskListeners = ArrayList<TaskListener>()

    private val logTaskListener by lazy {
        LogTaskListener()
    }

    internal lateinit var anchorsRuntime: AnchorsRuntime

    init {
        require(!TextUtils.isEmpty(id)) { "task's mId can't be empty" }
    }

    internal fun bindRuntime(runtime: AnchorsRuntime) {
        anchorsRuntime = runtime
    }

    fun addTaskListener(function: TaskListenerBuilder.() -> Unit) {
        taskListeners.add(TaskListenerBuilder().also(function))
    }

    fun addTaskListener(listener: TaskListener) {
        if (!taskListeners.contains(listener)) {
            taskListeners.add(listener)
        }
    }

    @Synchronized
    open fun start() {
        if (state != TaskState.IDLE) {
            throw RuntimeException("can no run task $id again!")
        }
        toStart()
        executeTime = System.currentTimeMillis()
        anchorsRuntime.executeTask(this)
    }

    override fun run() {
        toRunning()
        run(id)
        toFinish()
        val behindTaskIds = modifySons(behindTasks.map { it.id }.toTypedArray())
        tryCutoutBehind(behindTaskIds)
        notifyBehindTasks()
        release()
    }

    protected abstract fun run(name: String)

    protected open fun modifySons(behindTaskIds: Array<String>): Array<String> {
        return behindTaskIds
    }

    private fun toStart() {
        state = TaskState.START
        anchorsRuntime.setStateInfo(this)
        if (anchorsRuntime.debuggable) {
            logTaskListener.onStart(this)
        }
        for (listener in taskListeners) {
            listener.onStart(this)
        }
    }

    private fun toRunning() {
        state = TaskState.RUNNING
        anchorsRuntime.setStateInfo(this)
        anchorsRuntime.setThreadName(this, Thread.currentThread().name)
        if (anchorsRuntime.debuggable) {
            logTaskListener.onRunning(this)
        }
        for (listener in taskListeners) {
            listener.onRunning(this)
        }
    }

    private fun toFinish() {
        state = TaskState.FINISHED
        anchorsRuntime.setStateInfo(this)
        if (anchorsRuntime.debuggable) {
            logTaskListener.onFinish(this)
        }
        for (listener in taskListeners) {
            listener.onFinish(this)
        }
    }

    val dependTaskName: Set<String>
        get() {
            val result: MutableSet<String> = HashSet()
            for (task in dependTasks) {
                result.add(task.id)
            }
            return result
        }

    fun removeDepend(originTask: Task?) {
        if (dependTasks.contains(originTask)) {
            dependTasks.remove(originTask)
        }
    }

    fun updateBehind(updateTask: Task, originTask: Task?) {
        if (behindTasks.contains(originTask)) {
            behindTasks.remove(originTask)
        }
        behindTasks.add(updateTask)
    }

    /**
     * 后置触发, 和 [Task.dependOn] 方向相反，都可以设置依赖关系
     *
     * @param task
     */
    open fun behind(task: Task) {
        var task = task
        if (task !== this) {
            if (task is Project) {
                task = task.startTask
            }
            behindTasks.add(task)
            task.dependOn(this)
        }
    }

    open fun removeBehind(task: Task) {
        var task = task
        if (task !== this) {
            if (task is Project) {
                task = task.startTask
            }
            behindTasks.remove(task)
            task.removeDependence(this)
        }
    }

    /**
     * 前置条件, 和 [Task.behind] 方向相反，都可以设置依赖关系
     *
     * @param task
     */
    open fun dependOn(task: Task) {
        var task = task
        if (task !== this) {
            if (task is Project) {
                task = task.endTask
            }
            dependTasks.add(task)
            //防止外部所有直接调用dependOn无法构建完整图
            if (!task.behindTasks.contains(this)) {
                task.behindTasks.add(this)
            }
        }
    }

    open fun removeDependence(task: Task) {
        var task = task
        if (task !== this) {
            if (task is Project) {
                task = task.endTask
            }
            dependTasks.remove(task)
            if (task.behindTasks.contains(this)) {
                task.behindTasks.remove(this)
            }
        }
    }


    private fun removeDependenceChain(task: Task) {
        removeDependence(task)//删除前驱节点
        if (dependTasks.isEmpty()) {
            //如果没有其他依赖节点，删除后续任务的依赖关系
            for (behindTask in behindTasks) {
                behindTask.removeDependenceChain(this)
            }
            anchorsRuntime.removeAnchorTask(id)
        }
    }

    private fun tryCutoutBehind(behindTaskIds: Array<String>) {
        val cutOutTasks = behindTasks.filterNot { behindTaskIds.contains(it.id) }
        for (task in cutOutTasks) {
            task.removeDependenceChain(this)
        }
    }

    override fun compareTo(o: Task): Int {
        return compareTask(this, o)
    }

    /**
     * 通知后置者自己已经完成了
     */
    fun notifyBehindTasks() {
        if (this is LockableTask) {
            if (!this.successToUnlock()) {
                return
            }
        }
        if (behindTasks.isNotEmpty()) {
            if (behindTasks.size > 1) {
                val elements = behindTasks.toTypedArray()
                Arrays.sort(elements, anchorsRuntime.taskComparator)
                for (index in elements.indices) {
                    behindTasks[index] = elements[index]
                }
//                Collections.sort(behindTasks, anchorsRuntime.taskComparator)
            }
            //遍历记下来的任务，通知它们说存在的前置已经完成
            for (task in behindTasks) {
                task.dependTaskFinish(this)
            }
        }
    }

    /**
     * 依赖的任务已经完成
     * 比如 B -> A (B 依赖 A), A 完成之后调用该方法通知 B "A依赖已经完成了"
     * 当且仅当 B 的所有依赖都已经完成了, B 开始执行
     *
     * @param dependTask
     */
    @Synchronized
    fun dependTaskFinish(dependTask: Task?) {
        if (dependTasks.isEmpty()) {
            return
        }
        dependTasks.remove(dependTask)
        //所有前置任务都已经完成了
        if (dependTasks.isEmpty()) {
            start()
        }
    }

    open fun release() {
        state = TaskState.RELEASE
        anchorsRuntime.setStateInfo(this)
        anchorsRuntime.removeAnchorTask(id)
        anchorsRuntime.getTaskRuntimeInfo(id)?.clearTask()
        dependTasks.clear()
        behindTasks.clear()
        if (anchorsRuntime.debuggable) {
            logTaskListener.onRelease(this)
        }
        for (listener in taskListeners) {
            listener.onRelease(this)
        }
        taskListeners.clear()
    }
}