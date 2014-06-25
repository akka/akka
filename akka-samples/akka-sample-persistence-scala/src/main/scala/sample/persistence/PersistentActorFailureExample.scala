package sample.persistence

import akka.actor._
import akka.persistence._

object PersistentActorFailureExample extends App {
  class ExamplePersistentActor extends PersistentActor {
    override def persistenceId = "persistence-id"

    var received: List[String] = Nil // state

    def receiveCommand: Actor.Receive = {
      case "print" => println(s"received ${received.reverse}")
      case "boom"  => throw new Exception("boom")
      case payload: String =>
        persist(payload) { p => received = p :: received }

    }

    def receiveRecover: Actor.Receive = {
      case s: String => received = s :: received
    }
  }

  val system = ActorSystem("example")
  val persistentActor = system.actorOf(Props(classOf[ExamplePersistentActor]), "persistentActor-2")

  persistentActor ! "a"
  persistentActor ! "print"
  persistentActor ! "boom" // restart and recovery
  persistentActor ! "print"
  persistentActor ! "b"
  persistentActor ! "print"
  persistentActor ! "c"
  persistentActor ! "print"

  // Will print in a first run (i.e. with empty journal):

  // received List(a)
  // received List(a, b)
  // received List(a, b, c)

  // Will print in a second run:

  // received List(a, b, c, a)
  // received List(a, b, c, a, b)
  // received List(a, b, c, a, b, c)

  // etc ...

  Thread.sleep(1000)
  system.shutdown()
}
