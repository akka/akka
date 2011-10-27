package akka.docs.stm

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.util.duration._

//#imports
import akka.actor.Actor
import akka.event.Logging

//#imports

//#my-actor
class MyActor extends Actor {
  val log = Logging(app, this)
  def receive = {
    case "test" ⇒ log.info("received test")
    case _      ⇒ log.info("received unknown message")
  }
}
//#my-actor

class ActorDocSpec extends AkkaSpec {

  "creating actor with AkkaSpec.actorOf" in {
    //#creating-actorOf
    val myActor = actorOf[MyActor]
    //#creating-actorOf

    // testing the actor

    // TODO: convert docs to AkkaSpec(Configuration(...))
    val filter = EventFilter.custom {
      case e: Logging.Info ⇒ true
      case _               ⇒ false
    }
    app.mainbus.publish(TestEvent.Mute(filter))
    app.mainbus.subscribe(testActor, classOf[Logging.Info])

    myActor ! "test"
    expectMsgPF(1 second) { case Logging.Info(_, "received test") ⇒ true }

    myActor ! "unknown"
    expectMsgPF(1 second) { case Logging.Info(_, "received unknown message") ⇒ true }

    app.mainbus.unsubscribe(testActor)
    app.mainbus.publish(TestEvent.UnMute(filter))

    myActor.stop()
  }

  "creating actor with constructor" in {
    class MyActor(arg: String) extends Actor {
      def receive = { case _ ⇒ () }
    }

    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = actorOf(new MyActor("..."))
    //#creating-constructor

    myActor.stop()
  }
}
