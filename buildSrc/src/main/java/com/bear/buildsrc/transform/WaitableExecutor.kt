package com.bear.buildsrc.transform

import com.android.annotations.concurrency.Immutable
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask

/**
 * 线程池：允许等待所有提交任务执行完
 */
class WaitableExecutor internal constructor(
    private val forkJoinPool: ForkJoinPool,
    private val owned: Boolean
) {
    private val futureSet = Sets.newConcurrentHashSet<ForkJoinTask<*>>()

    /**
     * Submits a Callable for execution.
     *
     * @param callable the callable to run.
     * @throws java.util.concurrent.RejectedExecutionException if the task cannot be scheduled for execution
     */
    @Synchronized
    fun <T> execute(callable: Callable<T>): ForkJoinTask<T> {
        val submitted = forkJoinPool.submit(callable)
        val added = futureSet.add(submitted)
        Preconditions.checkState(added, "Failed to add task")
        return submitted
    }

    /**
     * Returns the number of tasks that have been submitted for execution but the results have not
     * been fetched yet.
     */
    val unprocessedTasksCount: Int
        get() = futureSet.size

    /**
     * Waits for all tasks to be executed. If a tasks throws an exception, it will be thrown from
     * this method inside a RuntimeException, preventing access to the result of the other threads.
     *
     * If you want to get the results of all tasks (result and/or exception), use [ ][.waitForAllTasks]
     *
     * To get the actual cause of the failure, examine the exception thrown. There are some
     * nuances to it though. If the exception was thrown on the same thread on which we wait for
     * completion, the [Throwable.cause] will be `null`. If the exception was
     * thrown on a different thread, the fork join pool mechanism will try to set the cause. Because
     * there is no access to this information, you probably want to check for the cause first, and
     * only if it is null, to check the exception thrown by this method.
     *
     * @param cancelRemaining if true, and a task fails, cancel all remaining tasks.
     * @return a list of all the return values from the tasks.
     * @throws InterruptedException if this thread was interrupted. Not if the tasks were
     * interrupted.
     */
    @Synchronized
    @Throws(InterruptedException::class)
    fun <T> waitForTasksWithQuickFail(cancelRemaining: Boolean): List<T> {
        val results: MutableList<T> = Lists.newArrayListWithCapacity(futureSet.size)
        try {
            for (future in futureSet) {
                results.add(future.join() as T)
            }
        } catch (e: RuntimeException) {
            if (cancelRemaining) {
                cancelAllTasks()
            }
            throw e
        } catch (e: Error) {
            if (cancelRemaining) {
                cancelAllTasks()
            }
            throw e
        } finally {
            futureSet.clear()
            if (owned) {
                forkJoinPool.shutdownNow()
            }
        }
        return results
    }

    /**
     * Waits for all tasks to be executed, and returns a [TaskResult] for each, containing
     * either the result or the exception thrown by the task.
     *
     * If a task is cancelled (and it threw InterruptedException) then the result for the task is
     * *not* included.
     *
     * @return a list of all the return values from the tasks.
     * @throws InterruptedException if this thread was interrupted. Not if the tasks were
     * interrupted.
     */
    @Synchronized
    @Throws(InterruptedException::class)
    fun <T> waitForAllTasks(): List<TaskResult<T>> {
        val results: MutableList<TaskResult<T>> = Lists.newArrayListWithCapacity(futureSet.size)
        try {
            for (future in futureSet) {
                // Get the result from the task.
                try {
                    results.add(TaskResult(future.join() as T))
                } catch (e: RuntimeException) {
                    // the original exception thrown by the task is the cause of this one.
                    results.add(TaskResult((if (e.cause != null) e.cause else e)!!))
                } catch (e: Error) {
                    results.add(TaskResult(e))
                }
            }
        } finally {
            futureSet.clear()
            if (owned) {
                forkJoinPool.shutdownNow()
            }
        }
        return results
    }

    /**
     * Cancel all remaining tasks.
     */
    @Synchronized
    fun cancelAllTasks() {
        for (future in futureSet) {
            future.cancel(true /*mayInterruptIfRunning*/)
        }
        futureSet.clear()
    }

    /**
     * Returns the parallelism of this executor i.e. how many tasks can run in parallel.
     */
    val parallelism: Int
        get() = forkJoinPool.parallelism

    @Immutable
    class TaskResult<T> {
        private val value: T?
        private val exception: Throwable?

        internal constructor(value: T?) {
            this.value = value
            exception = null
        }

        internal constructor(exception: Throwable) {
            value = null
            this.exception = Preconditions.checkNotNull(exception)
        }

        override fun toString(): String {
            return MoreObjects.toStringHelper(this)
                .add("value", value)
                .add("exception", exception)
                .toString()
        }
    }

    companion object {
        /**
         * Creates a new [WaitableExecutor] which uses a globally shared thread pool.
         *
         * Calling [.waitForAllTasks] on this instance will only block on tasks submitted to
         * this instance, but the tasks themselves will compete for threads with tasks submitted to
         * other [WaitableExecutor] instances created with this factory method.
         *
         * This is the recommended way of getting a [WaitableExecutor], since it makes sure the
         * total number of threads running doesn't exceed the value configured by the user.
         */
        fun useGlobalSharedThreadPool(): WaitableExecutor {
            return WaitableExecutor(ForkJoinPool.commonPool(), false)
        }

        /**
         * Creates a new [WaitableExecutor] which uses a newly allocated thread pool of the given
         * size.
         * If you can, use the [.useGlobalSharedThreadPool] factory method instead.
         *
         * @see .useGlobalSharedThreadPool
         */
        fun useNewFixedSizeThreadPool(nThreads: Int): WaitableExecutor {
            Preconditions.checkArgument(
                nThreads > 0,
                "Number of threads needs to be a positive number."
            )
            return WaitableExecutor(ForkJoinPool(nThreads), true)
        }

        /**
         * Creates a new [WaitableExecutor] that executes all jobs on the thread that schedules
         * them, removing any concurrency.
         *
         * @see com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService
         */
        @VisibleForTesting  // Temporarily used when debugging.
        fun useDirectExecutor(): WaitableExecutor {
            return WaitableExecutor(ForkJoinPool(1), true)
        }
    }
}