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
    case "test" ⇒ EventHandler.info(this, "received test")
    case _      ⇒ EventHandler.info(this, "received unknown message")
  }
}
//#my-actor

class ActorDocSpec extends WordSpec with MustMatchers with TestKit {

  "creating actor with Actor.actorOf" in {
    //#creating-actorOf
    val myActor = Actor.actorOf[MyActor]
    //#creating-actorOf

    // testing the actor

    EventHandler.notify(TestEvent.Mute(EventFilter.custom {
      case e: EventHandler.Info ⇒ true
      case _                    ⇒ false
    }))
    EventHandler.addListener(testActor)
    val eventLevel = EventHandler.level
    EventHandler.level = EventHandler.InfoLevel

    myActor ! "test"
    expectMsgPF(1 second) { case EventHandler.Info(_, "received test") ⇒ true }

    myActor ! "unknown"
    expectMsgPF(1 second) { case EventHandler.Info(_, "received unknown message") ⇒ true }

    EventHandler.level = eventLevel
    EventHandler.removeListener(testActor)
    EventHandler.notify(TestEvent.UnMuteAll)

    myActor.stop()
  }

  "creating actor with imported Actor._" in {
    //#creating-imported
    import akka.actor.Actor._

    val myActor = actorOf[MyActor]
    //#creating-imported

    myActor.stop()
  }

  "creating actor with constructor" in {
    class MyActor(arg: String) extends Actor {
      def receive = { case _ ⇒ () }
    }

    import akka.actor.Actor.actorOf

    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = actorOf(new MyActor("..."))
    //#creating-constructor

    myActor.stop()
  }
}
