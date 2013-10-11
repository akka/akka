/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import language.implicitConversions

import akka.actor.ActorSystem
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect.ClassTag
import scala.collection.immutable
import java.util.concurrent.TimeUnit.MILLISECONDS

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T)(implicit system: ActorSystem): T = {
    def now = System.currentTimeMillis

    system.eventStream.publish(TestEvent.Mute(eventFilters.to[immutable.Seq]))

    try {
      val result = block

      val testKitSettings = TestKitExtension(system)
      val stop = now + testKitSettings.TestEventFilterLeeway.toMillis
      val failed = eventFilters filterNot (_.awaitDone(Duration(stop - now, MILLISECONDS))) map ("Timeout (" + testKitSettings.TestEventFilterLeeway + ") waiting for " + _)
      if (failed.nonEmpty)
        throw new AssertionError("Filter completion error:\n" + failed.mkString("\n"))

      result
    } finally {
      system.eventStream.publish(TestEvent.UnMute(eventFilters.to[immutable.Seq]))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T)(implicit system: ActorSystem): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable](block: ⇒ Unit)(implicit system: ActorSystem, t: ClassTag[T]): Unit = EventFilter[T]() intercept (block)

  /**
   * Scala API. Scale timeouts (durations) during tests with the configured
   * 'akka.test.timefactor'.
   * Implicit conversion to add dilated function to Duration.
   * import scala.concurrent.duration._
   * import akka.testkit._
   * 10.milliseconds.dilated
   *
   * Corresponding Java API is available in TestKit.dilated
   */
  implicit def duration2TestDuration(duration: FiniteDuration) = new TestDuration(duration)
}
