package akka.testkit

import akka.event.EventHandler
import akka.event.EventHandler.{ Event, Error }
import akka.actor.Actor

sealed trait TestEvent

object TestEvent {
  object Mute {
    def apply(filter: EventFilter, filters: EventFilter*): Mute = new Mute(filter +: filters.toSeq)
  }
  case class Mute(filters: Seq[EventFilter]) extends TestEvent
  object UnMute {
    def apply(filter: EventFilter, filters: EventFilter*): UnMute = new UnMute(filter +: filters.toSeq)
  }
  case class UnMute(filters: Seq[EventFilter]) extends TestEvent
  case object UnMuteAll extends TestEvent
}

trait EventFilter {
  def apply(event: Event): Boolean
}

object EventFilter {

  def apply[A <: Throwable: Manifest](): EventFilter =
    ErrorFilter(manifest[A].erasure)

  def apply[A <: Throwable: Manifest](message: String): EventFilter =
    ErrorMessageFilter(manifest[A].erasure, message)

  def apply[A <: Throwable: Manifest](source: AnyRef): EventFilter =
    ErrorSourceFilter(manifest[A].erasure, source)

  def apply[A <: Throwable: Manifest](source: AnyRef, message: String): EventFilter =
    ErrorSourceMessageFilter(manifest[A].erasure, source, message)

  def custom(test: (Event) ⇒ Boolean): EventFilter =
    CustomEventFilter(test)
}

case class ErrorFilter(throwable: Class[_]) extends EventFilter {
  def apply(event: Event) = event match {
    case Error(cause, _, _) ⇒ throwable isInstance cause
    case _                  ⇒ false
  }
}

case class ErrorMessageFilter(throwable: Class[_], message: String) extends EventFilter {
  def apply(event: Event) = event match {
    case Error(cause, _, _) if !(throwable isInstance cause) ⇒ false
    case Error(cause, _, null) if cause.getMessage eq null   ⇒ cause.getStackTrace.length == 0
    case Error(cause, _, null)                               ⇒ cause.getMessage startsWith message
    case Error(cause, _, msg) ⇒
      (msg.toString startsWith message) || (cause.getMessage startsWith message)
    case _ ⇒ false
  }
}

case class ErrorSourceFilter(throwable: Class[_], source: AnyRef) extends EventFilter {
  def apply(event: Event) = event match {
    case Error(cause, instance, _) ⇒ (throwable isInstance cause) && (source eq instance)
    case _                         ⇒ false
  }
}

case class ErrorSourceMessageFilter(throwable: Class[_], source: AnyRef, message: String) extends EventFilter {
  def apply(event: Event) = event match {
    case Error(cause, instance, _) if !((throwable isInstance cause) && (source eq instance)) ⇒ false
    case Error(cause, _, null) if cause.getMessage eq null ⇒ cause.getStackTrace.length == 0
    case Error(cause, _, null) ⇒ cause.getMessage startsWith message
    case Error(cause, _, msg) ⇒
      (msg.toString startsWith message) || (cause.getMessage startsWith message)
    case _ ⇒ false
  }
}

case class CustomEventFilter(test: (Event) ⇒ Boolean) extends EventFilter {
  def apply(event: Event) = test(event)
}

class TestEventListener extends EventHandler.DefaultListener {
  import TestEvent._

  var filters: List[EventFilter] = Nil

  override def receive: Actor.Receive = ({
    case Mute(filters)                 ⇒ filters foreach addFilter
    case UnMute(filters)               ⇒ filters foreach removeFilter
    case UnMuteAll                     ⇒ filters = Nil
    case event: Event if filter(event) ⇒
  }: Actor.Receive) orElse super.receive

  def filter(event: Event): Boolean = filters exists (f ⇒ try { f(event) } catch { case e: Exception ⇒ false })

  def addFilter(filter: EventFilter): Unit = filters ::= filter

  def removeFilter(filter: EventFilter) {
    @scala.annotation.tailrec
    def removeFirst(list: List[EventFilter], zipped: List[EventFilter] = Nil): List[EventFilter] = list match {
      case head :: tail if head == filter ⇒ tail.reverse_:::(zipped)
      case head :: tail                   ⇒ removeFirst(tail, head :: zipped)
      case Nil                            ⇒ filters // filter not found, just return original list
    }
    filters = removeFirst(filters)
  }

}
