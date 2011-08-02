package akka

import akka.event.EventHandler

package object testkit {
  def filterEvents[T](eventFilters: Iterable[EventFilter])(block: ⇒ T): T = {
    EventHandler.notify(TestEvent.Mute(eventFilters.toSeq))
    try {
      block
    } finally {
      EventHandler.notify(TestEvent.UnMute(eventFilters.toSeq))
    }
  }

  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T): T = filterEvents(eventFilters.toSeq)(block)

  def filterException[T <: Throwable: Manifest](block: ⇒ Unit): Unit = filterEvents(Seq(EventFilter[T]))(block)
}
