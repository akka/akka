package akka.testkit

import java.util.concurrent.ThreadFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, FiniteDuration }

import com.typesafe.config.Config

import akka.actor.{ ActorSystem, Cancellable, Scheduler }
import akka.event.LoggingAdapter

/**
 * For testing: scheduler that does not look at the clock, but must be progressed manually by calling `timePasses`.
 *
 * This is not entirely realistic: jobs will be executed on the test thread instead of using the `ExecutionContext`, but does
 * allow for faster and less timing-sensitive specs..
 */
class ExplicitlyTriggeredScheduler(config: Config, log: LoggingAdapter, tf: ThreadFactory) extends Scheduler {

  case class Item(time: Long, interval: Option[FiniteDuration], runnable: Runnable)

  val currentTime = new AtomicLong()
  val scheduled = new ConcurrentHashMap[Item, Unit]()

  override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    schedule(initialDelay, Some(interval), runnable)

  override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable =
    schedule(delay, None, runnable)

  def timePasses(amount: FiniteDuration)(implicit system: ActorSystem) = {
    // TODO double-check if we really want/need dilation here
    val newTime = currentTime.get + amount.dilated.toMillis
    executeTasks(newTime)
    currentTime.set(newTime)
  }

  @tailrec
  private[testkit] final def executeTasks(runTo: Long): Unit = {
    scheduled
      .keySet
      .asScala
      .filter(_.time <= runTo)
      .toList
      .sortBy(_.time)
      .headOption match {
        case Some(task) ⇒
          currentTime.set(task.time)
          task.runnable.run()
          scheduled.remove(task)
          task.interval.foreach(i ⇒ scheduled.put(task.copy(time = task.time + i.toMillis), ()))

          // running the runnable might have scheduled new events
          executeTasks(runTo)
        case _ ⇒ // Done
      }
  }

  private def schedule(initialDelay: FiniteDuration, interval: Option[FiniteDuration], runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
    val item = Item(currentTime.get + initialDelay.toMillis, interval, runnable)
    scheduled.put(item, ())

    if (initialDelay == Duration.Zero)
      executeTasks(currentTime.get)

    new Cancellable {
      var cancelled = false

      override def cancel(): Boolean = {
        val before = scheduled.size
        scheduled.remove(item)
        cancelled = true
        before > scheduled.size
      }

      override def isCancelled: Boolean = cancelled
    }
  }

  override def maxFrequency: Double = 42
}
