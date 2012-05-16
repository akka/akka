/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.util.Duration
import org.jboss.netty.akka.util.{ TimerTask, HashedWheelTimer, Timeout ⇒ HWTimeout }
import akka.event.LoggingAdapter
import akka.dispatch.MessageDispatcher
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

//#scheduler
/**
 * An Akka scheduler service. This one needs one special behavior: if
 * Closeable, it MUST execute all outstanding tasks upon .close() in order
 * to properly shutdown all dispatchers.
 *
 * Furthermore, this timer service MUST throw IllegalStateException if it
 * cannot schedule a task. Once scheduled, the task MUST be executed. If
 * executed upon close(), the task may execute before its timeout.
 */
trait Scheduler {
  /**
   * Schedules a message to be sent repeatedly with an initial delay and
   * frequency. E.g. if you would like a message to be sent immediately and
   * thereafter every 500ms you would set delay=Duration.Zero and
   * frequency=Duration(500, TimeUnit.MILLISECONDS)
   *
   * Java & Scala API
   */
  def schedule(
    initialDelay: Duration,
    frequency: Duration,
    receiver: ActorRef,
    message: Any): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and a
   * frequency. E.g. if you would like the function to be run after 2 seconds
   * and thereafter every 100ms you would set delay = Duration(2, TimeUnit.SECONDS)
   * and frequency = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Scala API
   */
  def schedule(
    initialDelay: Duration, frequency: Duration)(f: ⇒ Unit): Cancellable

  /**
   * Schedules a function to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and frequency = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Java API
   */
  def schedule(
    initialDelay: Duration, frequency: Duration, runnable: Runnable): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * Java & Scala API
   */
  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has
   * to pass before the message is sent.
   *
   * Java & Scala API
   */
  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has
   * to pass before the function is run.
   *
   * Scala API
   */
  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable
}
//#scheduler

//#cancellable
/**
 * Signifies something that can be cancelled
 * There is no strict guarantee that the implementation is thread-safe,
 * but it should be good practice to make it so.
 */
trait Cancellable {
  /**
   * Cancels this Cancellable
   *
   * Java & Scala API
   */
  def cancel(): Unit

  /**
   * Returns whether this Cancellable has been cancelled
   *
   * Java & Scala API
   */
  def isCancelled: Boolean
}
//#cancellable

/**
 * Scheduled tasks (Runnable and functions) are executed with the supplied dispatcher.
 * Note that dispatcher is by-name parameter, because dispatcher might not be initialized
 * when the scheduler is created.
 *
 * The HashedWheelTimer used by this class MUST throw an IllegalStateException
 * if it does not enqueue a task. Once a task is queued, it MUST be executed or
 * returned from stop().
 */
class DefaultScheduler(hashedWheelTimer: HashedWheelTimer,
                       log: LoggingAdapter,
                       dispatcher: ⇒ MessageDispatcher) extends Scheduler with Closeable {

  def schedule(initialDelay: Duration, delay: Duration, receiver: ActorRef, message: Any): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new TimerTask with ContinuousScheduling {
          def run(timeout: HWTimeout) {
            receiver ! message
            // Check if the receiver is still alive and kicking before reschedule the task
            if (receiver.isTerminated) log.debug("Could not reschedule message to be sent because receiving actor has been terminated.")
            else scheduleNext(timeout, delay, continuousCancellable)
          }
        },
        initialDelay))
  }

  def schedule(initialDelay: Duration, delay: Duration)(f: ⇒ Unit): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new TimerTask with ContinuousScheduling with Runnable {
          def run = f
          def run(timeout: HWTimeout) {
            dispatcher.execute(this)
            scheduleNext(timeout, delay, continuousCancellable)
          }
        },
        initialDelay))
  }

  def schedule(initialDelay: Duration, delay: Duration, runnable: Runnable): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new TimerTask with ContinuousScheduling {
          def run(timeout: HWTimeout) {
            dispatcher.execute(runnable)
            scheduleNext(timeout, delay, continuousCancellable)
          }
        },
        initialDelay))
  }

  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable =
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new TimerTask() {
          def run(timeout: HWTimeout): Unit = dispatcher.execute(runnable)
        },
        delay))

  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable =
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new TimerTask {
          def run(timeout: HWTimeout): Unit = receiver ! message
        },
        delay))

  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable =
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new TimerTask with Runnable {
          def run = f
          def run(timeout: HWTimeout): Unit = dispatcher.execute(this)
        },
        delay))

  private trait ContinuousScheduling { this: TimerTask ⇒
    def scheduleNext(timeout: HWTimeout, delay: Duration, delegator: ContinuousCancellable) {
      try delegator.swap(timeout.getTimer.newTimeout(this, delay)) catch { case _: IllegalStateException ⇒ } // stop recurring if timer is stopped
    }
  }

  private def execDirectly(t: HWTimeout): Unit = {
    try t.getTask.run(t) catch {
      case e: InterruptedException ⇒ throw e
      case e: Exception            ⇒ log.error(e, "exception while executing timer task")
    }
  }

  def close(): Unit = {
    import scala.collection.JavaConverters._
    hashedWheelTimer.stop().asScala foreach execDirectly
  }
}

/**
 * Wrapper of a [[org.jboss.netty.akka.util.Timeout]] that delegates all
 * methods. Needed to be able to cancel continuous tasks,
 * since they create new Timeout for each tick.
 */
private[akka] class ContinuousCancellable extends AtomicReference[HWTimeout] with Cancellable {
  private[akka] def init(initialTimeout: HWTimeout): this.type = {
    assert(compareAndSet(null, initialTimeout))
    this
  }

  @tailrec private[akka] final def swap(newTimeout: HWTimeout): Unit = get match {
    case null                     ⇒ newTimeout.cancel()
    case some if some.isCancelled ⇒ cancel(); newTimeout.cancel()
    case some                     ⇒ if (!compareAndSet(some, newTimeout)) swap(newTimeout)
  }

  def isCancelled(): Boolean = get match {
    case null ⇒ true
    case some ⇒ isCancelled()
  }

  def cancel(): Unit =
    getAndSet(null) match {
      case null ⇒
      case some ⇒ some.cancel()
    }
}

private[akka] class DefaultCancellable(val timeout: HWTimeout) extends Cancellable {
  override def cancel(): Unit = timeout.cancel()
  override def isCancelled: Boolean = timeout.isCancelled
}
