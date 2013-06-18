/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
import akka.dispatch.AbstractNodeQueue

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
  extends Scheduler with Closeable {

  import Helpers.Requiring

  val WheelSize =
    config.getInt("akka.scheduler.ticks-per-wheel")
      .requiring(ticks ⇒ (ticks & (ticks - 1)) == 0, "ticks-per-wheel must be a power of 2")
  val TickDuration =
    Duration(config.getMilliseconds("akka.scheduler.tick-duration"), MILLISECONDS)
      .requiring(_ >= 10.millis || !Helpers.isWindows, "minimum supported akka.scheduler.tick-duration on Windows is 10ms")
      .requiring(_ >= 1.millis, "minimum supported akka.scheduler.tick-duration is ms")
  val ShutdownTimeout = Duration(config.getMilliseconds("akka.scheduler.shutdown-timeout"), MILLISECONDS)

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
  protected def getShutdownTimeout: FiniteDuration = ShutdownTimeout

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
                        runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    val preparedEC = executor.prepare()
    try new AtomicReference[Cancellable](InitialRepeatMarker) with Cancellable { self ⇒
      compareAndSet(InitialRepeatMarker, schedule(
        preparedEC,
        new AtomicLong(clock() + initialDelay.toNanos) with Runnable {
          override def run(): Unit = {
            try {
              runnable.run()
              val driftNanos = clock() - getAndAdd(delay.toNanos)
              if (self.get != null)
                swap(schedule(preparedEC, this, Duration.fromNanos(Math.max(delay.toNanos - driftNanos, 1))))
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
  }

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    try schedule(executor.prepare(), runnable, roundUp(delay))
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

  override def close(): Unit = Await.result(stop(), getShutdownTimeout) foreach execDirectly

  override val maxFrequency: Double = 1.second / TickDuration

  /*
   * BELOW IS THE ACTUAL TIMER IMPLEMENTATION
   */

  private val start = clock()
  private val tickNanos = TickDuration.toNanos
  private val wheelMask = WheelSize - 1
  private val queue = new TaskQueue

  private def schedule(ec: ExecutionContext, r: Runnable, delay: FiniteDuration): TimerTask =
    if (delay <= Duration.Zero) {
      if (stopped.get != null) throw new SchedulerException("cannot enqueue after timer shutdown")
      ec.execute(r)
      NotCancellable
    } else if (stopped.get != null) {
      throw new SchedulerException("cannot enqueue after timer shutdown")
    } else {
      val ticks = (delay.toNanos / tickNanos).toInt
      val task = new TaskHolder(r, ticks, ec)
      queue.add(task)
      if (stopped.get != null && task.cancel())
        throw new SchedulerException("cannot enqueue after timer shutdown")
      task
    }

  private val stopped = new AtomicReference[Promise[immutable.Seq[TimerTask]]]
  def stop(): Future[immutable.Seq[TimerTask]] = {
    val p = Promise[immutable.Seq[TimerTask]]()
    if (stopped.compareAndSet(null, p)) {
      // Interrupting the timer thread to make it shut down faster is not good since
      // it could be in the middle of executing the scheduled tasks, which might not
      // respond well to being interrupted.
      // Instead we just wait one more tick for it to finish.
      p.future
    } else Future.successful(Nil)
  }

  @volatile private var timerThread: Thread = threadFactory.newThread(new Runnable {

    var tick = 0
    val wheel = Array.fill(WheelSize)(new TaskQueue)

    private def clearAll(): immutable.Seq[TimerTask] = {
      def collect(q: TaskQueue, acc: Vector[TimerTask]): Vector[TimerTask] = {
        q.poll() match {
          case null ⇒ acc
          case x    ⇒ collect(q, acc :+ x)
        }
      }
      ((0 until WheelSize) flatMap (i ⇒ collect(wheel(i), Vector.empty))) ++ collect(queue, Vector.empty)
    }

    @tailrec
    private def checkQueue(): Unit = queue.pollNode() match {
      case null ⇒ ()
      case node ⇒
        if (node.value.ticks == 0) {
          node.value.executeTask()
        } else {
          val bucket = (tick + node.value.ticks) & wheelMask
          wheel(bucket).addNode(node)
        }
        checkQueue()
    }

    override final def run =
      try nextTick()
      catch {
        case t: Throwable ⇒
          log.error(t, "exception on LARS’ timer thread")
          stopped.get match {
            case null ⇒
              val thread = threadFactory.newThread(this)
              log.info("starting new LARS thread")
              try thread.start()
              catch {
                case e: Throwable ⇒
                  log.error(e, "LARS cannot start new thread, ship’s going down!")
                  stopped.set(Promise successful Nil)
                  clearAll()
              }
              timerThread = thread
            case p ⇒
              assert(stopped.compareAndSet(p, Promise successful Nil), "Stop signal violated in LARS")
              p success clearAll()
          }
          throw t
      }

    @tailrec final def nextTick(): Unit = {
      val sleepTime = start + tick * tickNanos - clock()

      if (sleepTime > 0) {
        // check the queue before taking a nap
        checkQueue()
        waitNanos(sleepTime)
      } else {
        val bucket = tick & wheelMask
        val tasks = wheel(bucket)
        val putBack = new TaskQueue

        @tailrec def executeBucket(): Unit = tasks.pollNode() match {
          case null ⇒ ()
          case node ⇒
            val task = node.value
            if (!task.isCancelled) {
              if (task.ticks > WheelSize) {
                task.ticks -= WheelSize
                putBack.addNode(node)
              } else task.executeTask()
            }
            executeBucket()
        }
        executeBucket()
        wheel(bucket) = putBack

        // check the queue now so that ticks==0 tasks can execute immediately
        checkQueue()

        tick += 1
      }
      stopped.get match {
        case null ⇒ nextTick()
        case p ⇒
          assert(stopped.compareAndSet(p, Promise successful Nil), "Stop signal violated in LARS")
          p success clearAll()
      }
    }
  })

  timerThread.start()
}

object LightArrayRevolverScheduler {
  private val taskOffset = unsafe.objectFieldOffset(classOf[TaskHolder].getDeclaredField("task"))

  private class TaskQueue extends AbstractNodeQueue[TaskHolder]

  /**
   * INTERNAL API
   */
  protected[actor] trait TimerTask extends Runnable with Cancellable

  /**
   * INTERNAL API
   */
  protected[actor] class TaskHolder(@volatile var task: Runnable, var ticks: Int, executionContext: ExecutionContext)
    extends TimerTask {

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

  private val InitialRepeatMarker = new Cancellable {
    def cancel(): Boolean = false
    def isCancelled: Boolean = false
  }
}

/**
 * A scheduler implementation based on a HashedWheelTimer.
 *
 * The HashedWheelTimer used by this class MUST throw an IllegalStateException
 * if it does not enqueue a task. Once a task is queued, it MUST be executed or
 * returned from stop().
 */
@deprecated("use LightArrayRevolverScheduler", "2.2")
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
    val preparedEC = executor.prepare()
    val continuousCancellable = new ContinuousCancellable
    continuousCancellable.init(
      hashedWheelTimer.newTimeout(
        new AtomicLong(System.nanoTime + initialDelay.toNanos) with HWTimerTask with ContinuousScheduling {
          override def run(timeout: HWTimeout): Unit =
            preparedEC.execute(new Runnable {
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

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    val preparedEC = executor.prepare()
    new DefaultCancellable(
      hashedWheelTimer.newTimeout(
        new HWTimerTask() { def run(timeout: HWTimeout): Unit = preparedEC.execute(runnable) },
        delay))
  }

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

@deprecated("use LightArrayRevolverScheduler", "2.2")
private[akka] object ContinuousCancellable {
  private class NullHWTimeout extends HWTimeout {
    override def getTimer: HWTimer = null
    override def getTask: HWTimerTask = null
    override def isExpired: Boolean = false
    override def isCancelled: Boolean = false
    override def cancel: Boolean = false
  }
  val initial: HWTimeout = new NullHWTimeout {
    override def cancel: Boolean = true
  }

  val cancelled: HWTimeout = new NullHWTimeout {
    override def isCancelled: Boolean = true
  }

  val expired: HWTimeout = new NullHWTimeout {
    override def isExpired: Boolean = true
  }
}
/**
 * Wrapper of a [[org.jboss.netty.akka.util.Timeout]] that delegates all
 * methods. Needed to be able to cancel continuous tasks,
 * since they create new Timeout for each tick.
 */
@deprecated("use LightArrayRevolverScheduler", "2.2")
private[akka] class ContinuousCancellable extends AtomicReference[HWTimeout](ContinuousCancellable.initial) with Cancellable {
  private[akka] def init(initialTimeout: HWTimeout): this.type = {
    compareAndSet(ContinuousCancellable.initial, initialTimeout)
    this
  }

  @tailrec private[akka] final def swap(newTimeout: HWTimeout): Unit = get match {
    case some if some.isCancelled ⇒ try cancel() finally newTimeout.cancel()
    case some                     ⇒ if (!compareAndSet(some, newTimeout)) swap(newTimeout)
  }

  override def isCancelled: Boolean = get().isCancelled()
  def cancel(): Boolean = getAndSet(ContinuousCancellable.cancelled).cancel()
}

@deprecated("use LightArrayRevolverScheduler", "2.2")
private[akka] class DefaultCancellable(timeout: HWTimeout) extends AtomicReference[HWTimeout](timeout) with Cancellable {
  @tailrec final override def cancel(): Boolean = {
    get match {
      case ContinuousCancellable.expired | ContinuousCancellable.cancelled ⇒ false // already done
      case x ⇒
        val y =
          if (!x.isCancelled && x.isExpired) ContinuousCancellable.expired
          else ContinuousCancellable.cancelled
        if (compareAndSet(x, y)) x.cancel()
        else cancel()
    }
  }

  override def isCancelled: Boolean = get().isCancelled
}
