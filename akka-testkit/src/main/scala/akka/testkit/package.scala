package akka

import akka.event.EventHandler

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T)(implicit app: AkkaApplication): T = {
    app.eventHandler.notify(TestEvent.Mute(eventFilters.toSeq))
    try {
      block
    } finally {
      app.eventHandler.notify(TestEvent.UnMute(eventFilters.toSeq))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T)(implicit app: AkkaApplication): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable](block: ⇒ Unit)(implicit app: AkkaApplication, m: Manifest[T]): Unit = filterEvents(Seq(EventFilter[T]))(block)
}
