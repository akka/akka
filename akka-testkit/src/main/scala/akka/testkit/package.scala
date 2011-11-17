package akka

import akka.actor.ActorSystem
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T)(implicit system: ActorSystem): T = {
    def now = System.currentTimeMillis

    system.eventStream.publish(TestEvent.Mute(eventFilters.toSeq))
    try {
      val result = block

      val stop = now + system.settings.TestEventFilterLeeway.toMillis
      val failed = eventFilters filterNot (_.awaitDone(Duration(stop - now, MILLISECONDS))) map ("Timeout (" + system.settings.TestEventFilterLeeway + ") waiting for " + _)
      if (failed.nonEmpty)
        throw new AssertionError("Filter completion error:\n" + failed.mkString("\n"))

      result
    } finally {
      system.eventStream.publish(TestEvent.UnMute(eventFilters.toSeq))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T)(implicit system: ActorSystem): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable](block: ⇒ Unit)(implicit system: ActorSystem, m: Manifest[T]): Unit = EventFilter[T]() intercept (block)
}
