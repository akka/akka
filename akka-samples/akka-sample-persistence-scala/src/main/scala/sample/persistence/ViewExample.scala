package sample.persistence

import scala.concurrent.duration._

import akka.actor._
import akka.persistence._

object ViewExample extends App {
  class ExamplePersistentActor extends PersistentActor {
    override def persistenceId = "persistentActor-5"

    var count = 1

    def receiveCommand: Actor.Receive = {
      case payload: String =>
        println(s"persistentActor received ${payload} (nr = ${count})")
        persist(payload + count) { evt =>
          count += 1
        }
    }

    def receiveRecover: Actor.Receive = {
      case _: String => count += 1
    }
  }

  class ExampleView extends View {
    private var numReplicated = 0

    override def persistenceId: String = "persistentActor-5"

    override def viewId = "view-5"

    def receive = {
      case "snap" =>
        saveSnapshot(numReplicated)
      case SnapshotOffer(metadata, snapshot: Int) =>
        numReplicated = snapshot
        println(s"view received snapshot offer ${snapshot} (metadata = ${metadata})")
      case Persistent(payload, _) =>
        numReplicated += 1
        println(s"view received ${payload} (num replicated = ${numReplicated})")
    }

  }

  val system = ActorSystem("example")

  val persistentActor = system.actorOf(Props(classOf[ExamplePersistentActor]))
  val view = system.actorOf(Props(classOf[ExampleView]))

  import system.dispatcher

  system.scheduler.schedule(Duration.Zero, 2.seconds, persistentActor, "scheduled")
  system.scheduler.schedule(Duration.Zero, 5.seconds, view, "snap")
}
