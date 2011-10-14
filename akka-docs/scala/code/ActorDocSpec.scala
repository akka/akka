package akka.docs.stm

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.util.duration._

//#imports
import akka.actor.Actor
import akka.event.EventHandler

//#imports

//#my-actor
class MyActor extends Actor {
  def receive = {
    case "test" ⇒ app.eventHandler.info(this, "received test")
    case _      ⇒ app.eventHandler.info(this, "received unknown message")
  }
}
//#my-actor

class ActorDocSpec extends AkkaSpec {

  "creating actor with AkkaSpec.createActor" in {
    //#creating-actorOf
    val myActor = createActor[MyActor]
    //#creating-actorOf

    // testing the actor

    // TODO: convert docs to AkkaSpec(Configuration(...))
    app.eventHandler.notify(TestEvent.Mute(EventFilter.custom {
      case e: EventHandler.Info ⇒ true
      case _                    ⇒ false
    }))
    app.eventHandler.addListener(testActor)
    val eventLevel = app.eventHandler.level
    app.eventHandler.level = EventHandler.InfoLevel

    myActor ! "test"
    expectMsgPF(1 second) { case EventHandler.Info(_, "received test") ⇒ true }

    myActor ! "unknown"
    expectMsgPF(1 second) { case EventHandler.Info(_, "received unknown message") ⇒ true }

    app.eventHandler.level = eventLevel
    app.eventHandler.removeListener(testActor)
    app.eventHandler.notify(TestEvent.UnMuteAll)

    myActor.stop()
  }

  "creating actor with constructor" in {
    class MyActor(arg: String) extends Actor {
      def receive = { case _ ⇒ () }
    }

    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = createActor(new MyActor("..."))
    //#creating-constructor

    myActor.stop()
  }
}
