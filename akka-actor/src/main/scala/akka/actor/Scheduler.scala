/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.util.JavaDurationConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
 * This exception is thrown by Scheduler.schedule* when scheduling is not
 * possible, e.g. after shutting down the Scheduler.
 */
private final case class SchedulerException(msg: String) extends akka.AkkaException(msg) with NoStackTrace

// The Scheduler trait is included in the documentation. KEEP THE LINES SHORT!!!
//#scheduler
/**
 * An Akka scheduler service. This one needs one special behavior: if
 * Closeable, it MUST execute all outstanding tasks upon .close() in order
 * to properly shutdown all dispatchers.
 *
 * Furthermore, this timer service MUST throw IllegalStateException if it
 * cannot schedule a task. Once scheduled, the task MUST be executed. If
 * executed upon close(), the task may execute before its timeout.
 *
 * Scheduler implementation are loaded reflectively at ActorSystem start-up
 * with the following constructor arguments:
 *  1) the system’s com.typesafe.config.Config (from system.settings.config)
 *  2) a akka.event.LoggingAdapter
 *  3) a java.util.concurrent.ThreadFactory
 *
 * Please note that this scheduler implementation is higly optimised for high-throughput
 * and high-frequency events. It is not to be confused with long-term schedulers such as
 * Quartz. The scheduler will throw an exception if attempts are made to schedule too far
 * into the future (which by default is around 8 months (`Int.MaxValue` seconds).
 */
trait Scheduler {
  /**
   * Schedules a message to be sent repeatedly with an initial delay and
   * frequency. E.g. if you would like a message to be sent immediately and
   * thereafter every 500ms you would set delay=Duration.Zero and
   * interval=Duration(500, TimeUnit.MILLISECONDS)
   *
   * Java & Scala API
   */
  final def schedule(
    initialDelay: FiniteDuration,
    interval:     FiniteDuration,
    receiver:     ActorRef,
    message:      Any)(implicit
    executor: ExecutionContext,
                       sender: ActorRef = Actor.noSender): Cancellable =
    schedule(initialDelay, interval, new Runnable {
      def run(): Unit = {
        receiver ! message
        if (receiver.isTerminated)
          throw SchedulerException("timer active for terminated actor")
      }
    })

  /**
   * Schedules a message to be sent repeatedly with an initial delay and
   * frequency. E.g. if you would like a message to be sent immediately and
   * thereafter every 500ms you would set delay=Duration.Zero and
   * interval=Duration(500, TimeUnit.MILLISECONDS)
   *
   * Java API
   */
  final def schedule(
    initialDelay: java.time.Duration,
    interval:     java.time.Duration,
    receiver:     ActorRef,
    message:      Any,
    executor:     ExecutionContext,
    sender:       ActorRef): Cancellable = {
    import JavaDurationConverters._
    schedule(initialDelay.asScala, interval.asScala, receiver, message)(executor, sender)
  }
  /**
   * Schedules a function to be run repeatedly with an initial delay and a
   * frequency. E.g. if you would like the function to be run after 2 seconds
   * and thereafter every 100ms you would set delay = Duration(2, TimeUnit.SECONDS)
   * and interval = Duration(100, TimeUnit.MILLISECONDS). If the execution of
   * the function takes longer than the interval, the subsequent execution will
   * start immediately after the prior one completes (there will be no overlap
   * of the function executions). In such cases, the actual execution interval
   * will differ from the interval passed to this method.
   *
   * If the function throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * Scala API
   */
  final def schedule(
    initialDelay: FiniteDuration,
    interval:     FiniteDuration)(f: ⇒ Unit)(
    implicit
    executor: ExecutionContext): Cancellable =
    schedule(initialDelay, interval, new Runnable { override def run(): Unit = f })

  /**
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and interval = Duration(100, TimeUnit.MILLISECONDS). If
   * the execution of the runnable takes longer than the interval, the
   * subsequent execution will start immediately after the prior one completes
   * (there will be no overlap of executions of the runnable). In such cases,
   * the actual execution interval will differ from the interval passed to this
   * method.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java API
   */
  def schedule(
    initialDelay: FiniteDuration,
    interval:     FiniteDuration,
    runnable:     Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a `Runnable` to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and interval = Duration(100, TimeUnit.MILLISECONDS). If
   * the execution of the runnable takes longer than the interval, the
   * subsequent execution will start immediately after the prior one completes
   * (there will be no overlap of executions of the runnable). In such cases,
   * the actual execution interval will differ from the interval passed to this
   * method.
   *
   * If the `Runnable` throws an exception the repeated scheduling is aborted,
   * i.e. the function will not be invoked any more.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java API
   */
  def schedule(
    initialDelay: java.time.Duration,
    interval:     java.time.Duration,
    runnable:     Runnable)(implicit executor: ExecutionContext): Cancellable = {
    import JavaDurationConverters._
    schedule(initialDelay.asScala, interval.asScala, runnable)
  }

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has
   * to pass before the message is sent.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java & Scala API
   */
  final def scheduleOnce(
    delay:    FiniteDuration,
    receiver: ActorRef,
    message:  Any)(implicit
    executor: ExecutionContext,
                   sender: ActorRef = Actor.noSender): Cancellable =
    scheduleOnce(delay, new Runnable {
      override def run(): Unit = receiver ! message
    })

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has
   * to pass before the message is sent.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java API
   */
  final def scheduleOnce(
    delay:    java.time.Duration,
    receiver: ActorRef,
    message:  Any,
    executor: ExecutionContext,
    sender:   ActorRef): Cancellable = {
    import JavaDurationConverters._
    scheduleOnce(delay.asScala, receiver, message)(executor, sender)
  }

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has
   * to pass before the function is run.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Scala API
   */
  final def scheduleOnce(delay: FiniteDuration)(f: ⇒ Unit)(
    implicit
    executor: ExecutionContext): Cancellable =
    scheduleOnce(delay, new Runnable { override def run(): Unit = f })

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java & Scala API
   */
  def scheduleOnce(
    delay:    FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * @throws IllegalArgumentException if the given delays exceed the maximum
   * reach (calculated as: `delay / tickNanos > Int.MaxValue`).
   *
   * Java & Scala API
   */
  def scheduleOnce(
    delay:    java.time.Duration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    import JavaDurationConverters._
    scheduleOnce(delay.asScala, runnable)(executor)
  }

  /**
   * The maximum supported task frequency of this scheduler, i.e. the inverse
   * of the minimum time interval between executions of a recurring task, in Hz.
   */
  def maxFrequency: Double

}
//#scheduler

// this one is just here so we can present a nice AbstractScheduler for Java
abstract class AbstractSchedulerBase extends Scheduler

//#cancellable
/**
 * Signifies something that can be cancelled
 * There is no strict guarantee that the implementation is thread-safe,
 * but it should be good practice to make it so.
 */
trait Cancellable {
  /**
   * Cancels this Cancellable and returns true if that was successful.
   * If this cancellable was (concurrently) cancelled already, then this method
   * will return false although isCancelled will return true.
   *
   * Java & Scala API
   */
  def cancel(): Boolean

  /**
   * Returns true if and only if this Cancellable has been successfully cancelled
   *
   * Java & Scala API
   */
  def isCancelled: Boolean
}
//#cancellable

object Cancellable {
  val alreadyCancelled: Cancellable = new Cancellable {
    def cancel(): Boolean = false
    def isCancelled: Boolean = true
  }
}
