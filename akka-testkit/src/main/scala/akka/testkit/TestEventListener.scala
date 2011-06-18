package akka.testkit

import akka.event.EventHandler
import akka.actor.Actor

sealed trait TestEvent

object TestEvent {
  case class Mute(filter: EventFilter = EventFilter.all) extends TestEvent
  case class UnMute(filter: EventFilter = EventFilter.all) extends TestEvent
}

case class EventFilter(throwable: Class[_] = classOf[Throwable], source: Option[AnyRef] = None, message: String = "") {
  import EventHandler._

  def apply(event: Event): Boolean = event match {
    case Error(cause, instance, message) ⇒
      (throwable isInstance cause) && (source map (_ eq instance) getOrElse true) &&
        ((message.toString startsWith this.message) || (Option(cause.getMessage) map (_ startsWith this.message) getOrElse false))
    case _ ⇒ false
  }
}

object EventFilter {
  val all = EventFilter()
}

class TestEventListener extends EventHandler.DefaultListener {
  import EventHandler._
  import TestEvent._

  var filters: List[EventFilter] = Nil

  override def receive: Receive = ({
    case Mute(filter)                  ⇒ addFilter(filter)
    case Mute                          ⇒ addFilter(EventFilter.all)
    case UnMute(filter)                ⇒ removeFilter(filter)
    case UnMute                        ⇒ addFilter(EventFilter.all)
    case event: Error if filter(event) ⇒ // Just test Error events
  }: Receive) orElse super.receive

  def filter(event: Event): Boolean = filters exists (_(event))

  def addFilter(filter: EventFilter): Unit = filters ::= filter

  def removeFilter(filter: EventFilter): Unit = {
    @scala.annotation.tailrec
    def removeFirst(list: List[EventFilter], zipped: List[EventFilter] = Nil): List[EventFilter] = list match {
      case head :: tail if head == filter ⇒ tail.reverse_:::(zipped)
      case head :: tail                   ⇒ removeFirst(tail, head :: zipped)
      case Nil                            ⇒ filters // filter not found, just return original list
    }
    filters = removeFirst(filters)
  }

}
