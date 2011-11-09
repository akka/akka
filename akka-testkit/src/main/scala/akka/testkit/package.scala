package akka

import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T)(implicit app: AkkaApplication): T = {
    def now = System.currentTimeMillis

    app.mainbus.publish(TestEvent.Mute(eventFilters.toSeq))
    try {
      val result = block

      val stop = now + app.AkkaConfig.TestEventFilterLeeway.toMillis
      val failed = eventFilters filterNot (_.awaitDone(Duration(stop - now, MILLISECONDS))) map ("Timeout waiting for " + _)
      if (failed.nonEmpty)
        throw new AssertionError("Filter completion error:\n" + failed.mkString("\n"))

      result
    } finally {
      app.mainbus.publish(TestEvent.UnMute(eventFilters.toSeq))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T)(implicit app: AkkaApplication): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable](block: ⇒ Unit)(implicit app: AkkaApplication, m: Manifest[T]): Unit = EventFilter[T]() intercept (block)
}
