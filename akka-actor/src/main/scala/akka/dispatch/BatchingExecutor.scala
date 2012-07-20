/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2011, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package akka.dispatch

import java.util.Collection
import java.util.concurrent.{ Callable, Executor, TimeUnit }
import scala.concurrent.util.Duration
import scala.concurrent._

/**
 * Mixin trait for an Executor
 * which groups multiple nested `Runnable.run()` calls
 * into a single Runnable passed to the original
 * Executor. This can be a useful optimization
 * because it bypasses the original context's task
 * queue and keeps related (nested) code on a single
 * thread which may improve CPU affinity. However,
 * if tasks passed to the Executor are blocking
 * or expensive, this optimization can prevent work-stealing
 * and make performance worse. Also, some ExecutionContext
 * may be fast enough natively that this optimization just
 * adds overhead.
 * The default ExecutionContext.global is already batching
 * or fast enough not to benefit from it; while
 * `fromExecutor` and `fromExecutorService` do NOT add
 * this optimization since they don't know whether the underlying
 * executor will benefit from it.
 * A batching executor can create deadlocks if code does
 * not use `scala.concurrent.blocking` when it should,
 * because tasks created within other tasks will block
 * on the outer task completing.
 * This executor may run tasks in any order, including LIFO order.
 * There are no ordering guarantees.
 */
private[akka] trait BatchingExecutor extends Executor {

  // invariant: if "_tasksLocal.get ne null" then we are inside
  // BatchingRunnable.run; if it is null, we are outside
  private val _tasksLocal = new ThreadLocal[List[Runnable]]()

  // only valid to call if _tasksLocal.get ne null
  private def push(runnable: Runnable): Unit =
    _tasksLocal.set(runnable :: _tasksLocal.get)

  // only valid to call if _tasksLocal.get ne null
  private def nonEmpty(): Boolean =
    _tasksLocal.get.nonEmpty

  // only valid to call if _tasksLocal.get ne null
  private def pop(): Runnable = {
    val tasks = _tasksLocal.get
    _tasksLocal.set(tasks.tail)
    tasks.head
  }

  private class BatchingBlockContext(previous: BlockContext) extends BlockContext {

    override def blockOn[T](thunk: ⇒ T)(implicit permission: CanAwait): T = {
      // if we know there will be blocking, we don't want to
      // keep tasks queued up because it could deadlock.
      _tasksLocal.get match {
        case null | Nil ⇒
        // null = not inside a BatchingRunnable
        // Nil = inside a BatchingRunnable, but nothing is queued up
        case list ⇒
          // inside a BatchingRunnable and there's a queue;
          // make a new BatchingRunnable and send it to
          // another thread
          _tasksLocal set Nil
          unbatchedExecute(new BatchingRunnable(list))
      }

      // now delegate the blocking to the previous BC
      previous.blockOn(thunk)
    }
  }

  private class BatchingRunnable(val initial: List[Runnable]) extends Runnable {
    // this method runs in the delegate ExecutionContext's thread
    override def run(): Unit = {
      require(_tasksLocal.get eq null)

      val bc = new BatchingBlockContext(BlockContext.current)
      BlockContext.withBlockContext(bc) {
        try {
          _tasksLocal set initial
          while (nonEmpty) {
            val next = pop()
            try {
              next.run()
            } catch {
              case t: Throwable ⇒
                // if one task throws, move the
                // remaining tasks to another thread
                // so we can throw the exception
                // up to the invoking executor
                val remaining = _tasksLocal.get
                _tasksLocal set Nil
                unbatchedExecute(new BatchingRunnable(remaining))
                throw t // rethrow
            }
          }
        } finally {
          _tasksLocal.remove()
          require(_tasksLocal.get eq null)
        }
      }
    }
  }

  private[this] def unbatchedExecute(r: Runnable): Unit = super.execute(r)

  abstract override def execute(runnable: Runnable): Unit = {
    _tasksLocal.get match {
      case null ⇒
        // outside BatchingRunnable.run: start a new batch
        unbatchedExecute(runnable)
      case _ ⇒
        // inside BatchingRunnable.run
        if (batchable(runnable))
          push(runnable) // add to existing batch
        else
          unbatchedExecute(runnable) // bypass batching mechanism
    }
  }

  /** Override this to define which runnables will be batched. */
  def batchable(runnable: Runnable): Boolean
}
