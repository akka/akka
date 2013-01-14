/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.Closeable
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.{ AtomicLong, AtomicReference, AtomicReferenceArray }

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._
import scala.util.control.{ NoStackTrace, NonFatal }

import com.typesafe.config.Config

import akka.event.LoggingAdapter
import akka.util.Helpers
import akka.util.Unsafe.{ instance ⇒ unsafe }
import akka.util.internal.{ HashedWheelTimer, Timeout ⇒ HWTimeout, Timer ⇒ HWTimer, TimerTask ⇒ HWTimerTask }

/**
 * This exception is thrown by Scheduler.schedule* when scheduling is not
 * possible, e.g. after shutting down the Scheduler.
 */
private case class SchedulerException(msg: String) extends akka.AkkaException(msg) with NoStackTrace

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
    interval: FiniteDuration,
    receiver: ActorRef,
    message: Any)(implicit executor: ExecutionContext,
                  sender: ActorRef = Actor.noSender): Cancellable =
    schedule(initialDelay, interval, new Runnable {
      def run = {
        receiver ! message
        if (receiver.isTerminated)
          throw new SchedulerException("timer active for terminated actor")
      }
    })

  /**
   * Schedules a function to be run repeatedly with an initial delay and a
   * frequency. E.g. if you would like the function to be run after 2 seconds
   * and thereafter every 100ms you would set delay = Duration(2, TimeUnit.SECONDS)
   * and interval = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Scala API
   */
  final def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration)(f: ⇒ Unit)(
      implicit executor: ExecutionContext): Cancellable =
    schedule(initialDelay, interval, new Runnable { override def run = f })

  /**
   * Schedules a function to be run repeatedly with an initial delay and
   * a frequency. E.g. if you would like the function to be run after 2
   * seconds and thereafter every 100ms you would set delay = Duration(2,
   * TimeUnit.SECONDS) and interval = Duration(100, TimeUnit.MILLISECONDS)
   *
   * Java API
   */
  def schedule(
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

  /**
   * Schedules a message to be sent once with a delay, i.e. a time period that has
   * to pass before the message is sent.
   *
   * Java & Scala API
   */
  final def scheduleOnce(
    delay: FiniteDuration,
    receiver: ActorRef,
    message: Any)(implicit executor: ExecutionContext,
                  sender: ActorRef = Actor.noSender): Cancellable =
    scheduleOnce(delay, new Runnable {
      override def run = receiver ! message
    })

  /**
   * Schedules a function to be run once with a delay, i.e. a time period that has
   * to pass before the function is run.
   *
   * Scala API
   */
  final def scheduleOnce(delay: FiniteDuration)(f: ⇒ Unit)(
    implicit executor: ExecutionContext): Cancellable =
    scheduleOnce(delay, new Runnable { override def run = f })

  /**
   * Schedules a Runnable to be run once with a delay, i.e. a time period that
   * has to pass before the runnable is executed.
   *
   * Java & Scala API
   */
  def scheduleOnce(
    delay: FiniteDuration,
    runnable: Runnable)(implicit executor: ExecutionContext): Cancellable

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

/**
 * This scheduler implementation is based on a revolving wheel of buckets,
 * like Netty’s HashedWheelTimer, which it advances at a fixed tick rate and
 * dispatches tasks it finds in the current bucket to their respective
 * ExecutionContexts. The tasks are held in TaskHolders, which upon
 * cancellation null out their reference to the actual task, leaving only this
 * shell to be cleaned up when the wheel reaches that bucket next time. This
 * enables the use of a simple linked list to chain the TaskHolders off the
 * wheel.
 *
 * Also noteworthy is that this scheduler does not obtain a current time stamp
 * when scheduling single-shot tasks, instead it always rounds up the task
 * delay to a full multiple of the TickDuration. This means that tasks are
 * scheduled possibly one tick later than they could be (if checking that
 * “now() + delay <= nextTick” were done).
 */
class LightArrayRevolverScheduler(config: Config,
                                  log: LoggingAdapter,
                                  threadFactory: ThreadFactory)
  extends {
    val WheelShift = {
      val ticks = config.getInt("akka.scheduler.ticks-per-wheel")
      val shift = 31 - Integer.numberOfLeadingZeros(ticks)
      if ((ticks & (ticks - 1)) != 0) throw new akka.ConfigurationException("ticks-per-wheel must be a power of 2")
      shift
    }
    val TickDuration = Duration(config.getMilliseconds("akka.scheduler.tick-duration"), MILLISECONDS)
    val ShutdownTimeout = Duration(config.getMilliseconds("akka.scheduler.shutdown-timeout"), MILLISECONDS)
  } with AtomicReferenceArray[LightArrayRevolverScheduler.TaskHolder](1 << WheelShift) with Scheduler with Closeable {

  import LightArrayRevolverScheduler._

  private val oneNs = Duration.fromNanos(1l)
  private def roundUp(d: FiniteDuration): FiniteDuration =
    try {
      ((d + TickDuration - oneNs) / TickDuration).toLong * TickDuration
    } catch {
      case _: IllegalArgumentException ⇒ d // rouding up Long.MaxValue.nanos overflows
    }

  /**
   * Clock implementation is replaceable (for testing); the implementation must
   * return a monotonically increasing series of Long nanoseconds.
   */
  protected def clock(): Long = System.nanoTime

  /**
   * Overridable for tests
   */
  protected def waitNanos(nanos: Long): Unit = {
    // see http://www.javamex.com/tutorials/threads/sleep_issues.shtml
    val sleepMs = if (Helpers.isWindows) (nanos + 4999999) / 10000000 * 10 else (nanos + 999999) / 1000000
    try Thread.sleep(sleepMs) catch {
      case _: InterruptedException ⇒ Thread.currentThread.interrupt() // we got woken up
    }
  }

  override def schedule(initialDelay: FiniteDuration,
                        delay: FiniteDuration,
                        runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    try new AtomicReference[Cancellable] with Cancellable { self ⇒
      set(schedule(
        new AtomicLong(clock() + initialDelay.toNanos) with Runnable {
          override def run(): Unit = {
            try {
              runnable.run()
              val driftNanos = clock() - getAndAdd(delay.toNanos)
              if (self.get != null)
                swap(schedule(this, Duration.fromNanos(Math.max(delay.toNanos - driftNanos, 1))))
            } catch {
              case _: SchedulerException ⇒ // ignore failure to enqueue or terminated target actor
            }
          }
        }, roundUp(initialDelay)))

      @tailrec private def swap(c: Cancellable): Unit = {
        get match {
          case null ⇒ if (c != null) c.cancel()
          case old  ⇒ if (!compareAndSet(old, c)) swap(c)
        }
      }

      @tailrec final def cancel(): Boolean = {
        get match {
          case null ⇒ false
          case c ⇒
            if (c.cancel()) compareAndSet(c, null)
            else compareAndSet(c, null) || cancel()
        }
      }

      override def isCancelled: Boolean = get == null
    } catch {
      case SchedulerException(msg) ⇒ throw new IllegalStateException(msg)
    }

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    try schedule(runnable, roundUp(delay))
    catch {
      case SchedulerException(msg) ⇒ throw new IllegalStateException(msg)
    }

  private def execDirectly(t: TimerTask): Unit = {
    try t.run() catch {
      case e: InterruptedException ⇒ throw e
      case _: SchedulerException   ⇒ // ignore terminated actors
      case NonFatal(e)             ⇒ log.error(e, "exception while executing timer task")
    }
  }

  override def close(): Unit = Await.result(stop(), ShutdownTimeout) foreach execDirectly

  override val maxFrequency: Double = 1.second / TickDuration

  /*
   * BELOW IS THE ACTUAL TIMER IMPLEMENTATION
   */

  private val start = clock()
  private val tickNanos = TickDuration.toNanos
  private val wheelMask = length() - 1
  @volatile private var currentBucket = 0

  private def schedule(r: Runnable, delay: FiniteDuration)(implicit ec: ExecutionContext): TimerTask =
    if (delay <= Duration.Zero) {
      if (stopped.get != null) throw new SchedulerException("cannot enqueue after timer shutdown")
      ec.execute(r)
      NotCancellable
    } else {
      val ticks = (delay.toNanos / tickNanos).toInt
      val rounds = (ticks >> WheelShift).toInt

      /*
       * works as follows:
       * - ticks are calculated to be never “too early”
       * - base off of currentBucket, even after that was moved in the meantime
       * - timer thread will swap in Pause, increment currentBucket, swap in null
       * - hence spin on Pause, else normal CAS
       * - stopping will set all buckets to Pause (in clearAll), so we need only check there
       */
      @tailrec
      def rec(t: TaskHolder): TimerTask = {
        val bucket = (currentBucket + ticks) & wheelMask
        get(bucket) match {
          case Pause ⇒
            if (stopped.get != null) throw new SchedulerException("cannot enqueue after timer shutdown")
            rec(t)
          case tail ⇒
            t.next = tail
            if (compareAndSet(bucket, tail, t)) t
            else rec(t)
        }
      }

      rec(new TaskHolder(r, null, rounds))
    }

  private val stopped = new AtomicReference[Promise[immutable.Seq[TimerTask]]]
  def stop(): Future[immutable.Seq[TimerTask]] =
    if (stopped.compareAndSet(null, Promise())) {
      timerThread.interrupt()
      stopped.get.future
    } else Future.successful(Nil)

  private def clearAll(): immutable.Seq[TimerTask] = {
    def collect(curr: TaskHolder, acc: Vector[TimerTask]): Vector[TimerTask] = {
      curr match {
        case null ⇒ acc
        case x    ⇒ collect(x.next, acc :+ x)
      }
    }
    (0 until length()) flatMap (i ⇒ collect(getAndSet(i, Pause), Vector.empty))
  }

  @volatile private var timerThread: Thread = threadFactory.newThread(new Runnable {
    var tick = 0
    override final def run =
      try nextTick()
      catch {
        case t: Throwable ⇒
          val thread = threadFactory.newThread(this)
          try thread.start()
          finally timerThread = thread
          throw t
      }
    @tailrec final def nextTick(): Unit = {
      val sleepTime = start + tick * tickNanos - clock()

      if (sleepTime > 0) {
        waitNanos(sleepTime)
      } else {
        // first get the list of tasks out and turn the wheel
        val bucket = currentBucket
        val tasks = getAndSet(bucket, Pause)
        val next = (bucket + 1) & wheelMask
        currentBucket = next
        set(bucket, if (tasks eq null) Empty else null)

        // then process the tasks and keep the non-ripe ones in a list
        var last: TaskHolder = null // the last element of the putBack list
        @tailrec def rec1(task: TaskHolder, nonRipe: TaskHolder): TaskHolder = {
          if ((task eq null) || (task eq Empty)) nonRipe
          else if (task.isCancelled) rec1(task.next, nonRipe)
          else if (task.rounds > 0) {
            task.rounds -= 1

            val next = task.next
            task.next = nonRipe

            if (last == null) last = task
            rec1(next, task)
          } else {
            task.executeTask()
            rec1(task.next, nonRipe)
          }
        }
        val putBack = rec1(tasks, null)

        // finally put back the non-ripe ones, who had their rounds decremented
        @tailrec def rec2() {
          val tail = get(bucket)
          last.next = tail
          if (!compareAndSet(bucket, tail, putBack)) rec2()
        }
        if (last != null) rec2()

        // and off to the next tick
        tick += 1
      }
      stopped.get match {
        case null ⇒ nextTick()
        case x    ⇒ x success clearAll()
      }
    }
  })

  timerThread.start()
}

object LightArrayRevolverScheduler {
  private val taskOffset = unsafe.objectFieldOffset(classOf[TaskHolder].getDeclaredField("task"))

  /**
   * INTERNAL API
   */
  protected[actor] trait TimerTask extends Runnable with Cancellable

  /**
   * INTERNAL API
   */
  protected[actor] class TaskHolder(@volatile var task: Runnable,
                           @volatile var next: TaskHolder,
                           @volatile var rounds: Int)(
                             implicit executionContext: ExecutionContext) extends TimerTask {
    @tailrec
    private final def extractTask(cancel: Boolean): Runnable = {
      task match {
        case null | CancelledTask ⇒ null // null means expired
        case x ⇒
          if (unsafe.compareAndSwapObject(this, taskOffset, x, if (cancel) CancelledTask else null)) x
          else extractTask(cancel)
      }
    }

    private[akka] final def executeTask(): Boolean = extractTask(cancel = false) match {
      case null | CancelledTask ⇒ false
      case other ⇒
        try {
          executionContext execute other
          true
        } catch {
          case _: InterruptedException ⇒ { Thread.currentThread.interrupt(); false }
          case NonFatal(e)             ⇒ { executionContext.reportFailure(e); false }
        }
    }

    /**
     * utility method to directly run the task, e.g. as clean-up action
     */
    def run(): Unit = extractTask(cancel = false) match {
      case null ⇒
      case r    ⇒ r.run()
    }

    override def cancel(): Boolean = extractTask(cancel = true) != null

    override def isCancelled: Boolean = task eq CancelledTask
  }

  private val CancelledTask = new Runnable { def run = () }

  private val NotCancellable = new TimerTask {
    def cancel(): Boolean = false
    def isCancelled: Boolean = false
    def run(): Unit = ()
  }
  // marker object during wheel movement
  private val Pause = new TaskHolder(null, null, 0)(null)
  // we need two empty tokens so wheel passing can be detected in schedule()
  private val Empty = new TaskHolder(null, null, 0)(null)
}

/**
 * Scheduled tasks (Runnable and functions) are executed with the supplied dispatcher.
 * Note that dispatcher is by-name parameter, because dispatcher might not be initialized
 * when the scheduler is created.
 *
 * The HashedWheelTimer used by this class MUST throw an IllegalStateException
 * if it does not enqueue a task. Once a task is queued, it MUST be executed or
 * returned from stop().
 */
class DefaultScheduler(config: Config,
                       log: LoggingAdapter,
                       threadFactory: ThreadFactory) extends Scheduler with Closeable {

  val TicksPerWheel = {
    val ticks = config.getInt("akka.scheduler.ticks-per-wheel")
    val shift = 31 - Integer.numberOfLeadingZeros(ticks)
    if ((ticks & (ticks - 1)) != 0) throw new akka.ConfigurationException("ticks-per-wheel must be a power of 2")
    ticks
  }
  val TickDuration = Duration(config.getMilliseconds("akka.scheduler.tick-duration"), MILLISECONDS)

  private val hashedWheelTimer = new HashedWheelTimer(log, threadFactory, TickDuration, TicksPerWheel)

  override def schedule(initialDelay: FiniteDuration,
                        delay: FiniteDuration,
                        runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new AtomicLong(System.nanoTime + initialDelay.toNanos) with HWTimerTask with ContinuousScheduling {
          override def run(timeout: HWTimeout): Unit =
            executor.execute(new Runnable {
              override def run = {
                try {
                  runnable.run()
                  val driftNanos = System.nanoTime - getAndAdd(delay.toNanos)
                  scheduleNext(timeout, Duration.fromNanos(Math.max(delay.toNanos - driftNanos, 1)), continuousCancellable)
                } catch {
                  case _: SchedulerException ⇒ // actor target terminated
                }
              }
            })
        },
        initialDelay))
  }

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new HWTimerTask() { def run(timeout: HWTimeout): Unit = executor.execute(runnable) },
        delay))

  private trait ContinuousScheduling { this: HWTimerTask ⇒
    def scheduleNext(timeout: HWTimeout, delay: FiniteDuration, delegator: ContinuousCancellable) {
      try delegator.swap(timeout.getTimer.newTimeout(this, delay)) catch { case _: IllegalStateException ⇒ } // stop recurring if timer is stopped
    }
  }

  private def execDirectly(t: HWTimeout): Unit = {
    try t.getTask.run(t) catch {
      case e: InterruptedException ⇒ throw e
      case e: Exception            ⇒ log.error(e, "exception while executing timer task")
    }
  }

  override def close(): Unit = {
    val i = hashedWheelTimer.stop().iterator()
    while (i.hasNext) execDirectly(i.next())
  }

  override def maxFrequency: Double = 1.second / TickDuration
}

private[akka] object ContinuousCancellable {
  val initial: HWTimeout = new HWTimeout {
    override def getTimer: HWTimer = null
    override def getTask: HWTimerTask = null
    override def isExpired: Boolean = false
    override def isCancelled: Boolean = false
    override def cancel: Boolean = true
  }

  val cancelled: HWTimeout = new HWTimeout {
    override def getTimer: HWTimer = null
    override def getTask: HWTimerTask = null
    override def isExpired: Boolean = false
    override def isCancelled: Boolean = true
    override def cancel: Boolean = false
  }
}
/**
 * Wrapper of a [[org.jboss.netty.akka.util.Timeout]] that delegates all
 * methods. Needed to be able to cancel continuous tasks,
 * since they create new Timeout for each tick.
 */
private[akka] class ContinuousCancellable extends AtomicReference[HWTimeout](ContinuousCancellable.initial) with Cancellable {
  private[akka] def init(initialTimeout: HWTimeout): this.type = {
    compareAndSet(ContinuousCancellable.initial, initialTimeout)
    this
  }

  @tailrec private[akka] final def swap(newTimeout: HWTimeout): Unit = get match {
    case some if some.isCancelled ⇒ try cancel() finally newTimeout.cancel()
    case some                     ⇒ if (!compareAndSet(some, newTimeout)) swap(newTimeout)
  }

  def isCancelled(): Boolean = get().isCancelled()
  def cancel(): Boolean = getAndSet(ContinuousCancellable.cancelled).cancel()
}

private[akka] class DefaultCancellable(timeout: HWTimeout) extends AtomicReference[HWTimeout](timeout) with Cancellable {
  override def cancel(): Boolean = getAndSet(ContinuousCancellable.cancelled).cancel()
  override def isCancelled: Boolean = get().isCancelled
}
