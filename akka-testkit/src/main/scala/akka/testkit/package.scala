package akka

import akka.event.EventHandler

package object testkit {
  def filterEvents[T](eventFilters: EventFilter*)(block: ⇒ T): T = {
    EventHandler.notify(TestEvent.Mute(eventFilters.toSeq))
    try {
      block
    } finally {
      EventHandler.notify(TestEvent.UnMute(eventFilters.toSeq))
    }
  }
}
