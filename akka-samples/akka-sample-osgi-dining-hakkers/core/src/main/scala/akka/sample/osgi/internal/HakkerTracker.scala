package akka.sample.osgi.internal

import akka.persistence.EventsourcedProcessor
import akka.actor.ActorRef
import akka.sample.osgi.api.HakkerStateChange
import akka.sample.osgi.api.SubscribeToHakkerStateChanges
import akka.sample.osgi.api.EatingCount
import akka.sample.osgi.api.GetEatingCount
import akka.sample.osgi.api.TrackHakker

object HakkerTracker {
  sealed trait DomainEvent
  case class StartedEating(name: String) extends DomainEvent
  case class StoppedEating(name: String) extends DomainEvent

  object State {
    val empty: State = new State(Map.empty)
  }
  case class State private (eatingCounts: Map[String, Int]) {
    def updated(event: DomainEvent): State = event match {
      case StartedEating(name) =>
        val c = eatingCounts.getOrElse(name, 0) + 1
        copy(eatingCounts = eatingCounts + (name -> c))
      case StoppedEating(name) =>
        this
    }
  }
}

class HakkerTracker extends EventsourcedProcessor {
  import HakkerTracker._

  var state = State.empty

  override def receiveRecover: Receive = {
    case evt: DomainEvent =>
      state = state.updated(evt)
  }

  override def receiveCommand: Receive = {
    case TrackHakker(hakker) =>
      hakker ! SubscribeToHakkerStateChanges

    case HakkerStateChange(name, _, "eating") =>
      persist(StartedEating(name)) { evt =>
        state = state.updated(evt)
      }

    case HakkerStateChange(name, "eating", _) =>
      persist(StoppedEating(name)) { evt =>
        state = state.updated(evt)
      }

    case GetEatingCount(name) =>
      sender ! EatingCount(name, 17)
  }

}