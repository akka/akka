package akka

import language.implicitConversions

import akka.actor.ActorSystem
import scala.concurrent.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import scala.reflect.ClassTag

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T)(implicit system: ActorSystem): T = {
    def now = System.currentTimeMillis

    system.eventStream.publish(TestEvent.Mute(eventFilters.toSeq))
    try {
      val result = block

      val testKitSettings = TestKitExtension(system)
      val stop = now + testKitSettings.TestEventFilterLeeway.toMillis
      val failed = eventFilters filterNot (_.awaitDone(Duration(stop - now, MILLISECONDS))) map ("Timeout (" + testKitSettings.TestEventFilterLeeway + ") waiting for " + _)
      if (failed.nonEmpty)
        throw new AssertionError("Filter completion error:\n" + failed.mkString("\n"))

      result
    } finally {
      system.eventStream.publish(TestEvent.UnMute(eventFilters.toSeq))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T)(implicit system: ActorSystem): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable](block: ⇒ Unit)(implicit system: ActorSystem, t: ClassTag[T]): Unit = EventFilter[T]() intercept (block)

  /**
   * Scala API. Scale timeouts (durations) during tests with the configured
   * 'akka.test.timefactor'.
   * Implicit conversion to add dilated function to Duration.
   * import scala.concurrent.util.duration._
   * import akka.testkit._
   * 10.milliseconds.dilated
   *
   * Corresponding Java API is available in TestKit.dilated
   */
  implicit def duration2TestDuration(duration: Duration) = new TestDuration(duration)

  /**
   * Wrapper for implicit conversion to add dilated function to Duration.
   */
  class TestDuration(duration: Duration) {
    def dilated(implicit system: ActorSystem): Duration = {
      duration * TestKitExtension(system).TestTimeFactor
    }
  }
}
