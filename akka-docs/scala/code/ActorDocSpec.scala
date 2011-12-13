package akka.docs.actor

//#imports1
import akka.actor.Actor
import akka.event.Logging
import akka.dispatch.Future

//#imports1

//#imports2
import akka.actor.ActorSystem
//#imports2

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.util.duration._

//#my-actor
class MyActor extends Actor {
  val log = Logging(context.system, this)
  def receive = {
    case "test" ⇒ log.info("received test")
    case _      ⇒ log.info("received unknown message")
  }
}
//#my-actor

case class DoIt(msg: ImmutableMessage)
case class Message(s: String)

//#context-actorOf
class FirstActor extends Actor {
  val myActor = context.actorOf[MyActor]
  //#context-actorOf
  //#anonymous-actor
  def receive = {
    case m: DoIt ⇒
      context.actorOf(new Actor {
        def receive = {
          case DoIt(msg) ⇒
            val replyMsg = doSomeDangerousWork(msg)
            sender ! replyMsg
            self.stop()
        }
        def doSomeDangerousWork(msg: ImmutableMessage): String = { "done" }
      }) ! m

    case replyMsg: String ⇒ sender ! replyMsg
  }
  //#anonymous-actor
}

//#system-actorOf
object Main extends App {
  val system = ActorSystem("MySystem")
  val myActor = system.actorOf[MyActor]
  //#system-actorOf
}

class ReplyException extends Actor {
  def receive = {
    case _ ⇒
      //#reply-exception
      try {
        val result = operation()
        sender ! result
      } catch {
        case e: Exception ⇒
          sender ! akka.actor.Status.Failure(e)
          throw e
      }
    //#reply-exception
  }

  def operation(): String = { "Hi" }

}

//#swapper
case object Swap
class Swapper extends Actor {
  import context._
  val log = Logging(system, this)

  def receive = {
    case Swap ⇒
      log.info("Hi")
      become {
        case Swap ⇒
          log.info("Ho")
          unbecome() // resets the latest 'become' (just for fun)
      }
  }
}

object SwapperApp extends App {
  val system = ActorSystem("SwapperSystem")
  val swap = system.actorOf[Swapper]
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
}
//#swapper

//#receive-orElse
import akka.actor.Actor.Receive

abstract class GenericActor extends Actor {
  // to be defined in subclassing actor
  def specificMessageHandler: Receive

  // generic message handler
  def genericMessageHandler: Receive = {
    case event ⇒ printf("generic: %s\n", event)
  }

  def receive = specificMessageHandler orElse genericMessageHandler
}

class SpecificActor extends GenericActor {
  def specificMessageHandler = {
    case event: MyMsg ⇒ printf("specific: %s\n", event.subject)
  }
}

case class MyMsg(subject: String)
//#receive-orElse

class ActorDocSpec extends AkkaSpec(Map("akka.loglevel" -> "INFO")) {

  "import context" in {
    //#import-context
    class FirstActor extends Actor {
      import context._
      val myActor = actorOf[MyActor]
      def receive = {
        case x ⇒ myActor ! x
      }
    }
    //#import-context

    val first = system.actorOf(new FirstActor)
    first.stop()

  }

  "creating actor with AkkaSpec.actorOf" in {
    val myActor = system.actorOf[MyActor]

    // testing the actor

    // TODO: convert docs to AkkaSpec(Map(...))
    val filter = EventFilter.custom {
      case e: Logging.Info ⇒ true
      case _               ⇒ false
    }
    system.eventStream.publish(TestEvent.Mute(filter))
    system.eventStream.subscribe(testActor, classOf[Logging.Info])

    myActor ! "test"
    expectMsgPF(1 second) { case Logging.Info(_, "received test") ⇒ true }

    myActor ! "unknown"
    expectMsgPF(1 second) { case Logging.Info(_, "received unknown message") ⇒ true }

    system.eventStream.unsubscribe(testActor)
    system.eventStream.publish(TestEvent.UnMute(filter))

    myActor.stop()
  }

  "creating actor with constructor" in {
    class MyActor(arg: String) extends Actor {
      def receive = { case _ ⇒ () }
    }

    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = system.actorOf(new MyActor("..."))
    //#creating-constructor

    myActor.stop()
  }

  "creating actor with Props" in {
    //#creating-props
    import akka.actor.Props
    val dispatcher = system.dispatcherFactory.newFromConfig("my-dispatcher")
    val myActor = system.actorOf(Props[MyActor].withDispatcher(dispatcher), name = "myactor")
    //#creating-props

    myActor.stop()
  }

  "using ask" in {
    //#using-ask
    class MyActor extends Actor {
      def receive = {
        case x: String ⇒ sender ! x.toUpperCase
        case n: Int    ⇒ sender ! (n + 1)
      }
    }

    val myActor = system.actorOf(new MyActor)
    implicit val timeout = system.settings.ActorTimeout
    val future = myActor ? "hello"
    for (x ← future) println(x) //Prints "hello"

    val result: Future[Int] = for (x ← (myActor ? 3).mapTo[Int]) yield { 2 * x }
    //#using-ask

    myActor.stop()
  }

  "using receiveTimeout" in {
    //#receive-timeout
    import akka.actor.ReceiveTimeout
    import akka.util.duration._
    class MyActor extends Actor {
      context.receiveTimeout = Some(30 seconds)
      def receive = {
        case "Hello"        ⇒ //...
        case ReceiveTimeout ⇒ throw new RuntimeException("received timeout")
      }
    }
    //#receive-timeout
  }

  "using hot-swap" in {
    //#hot-swap-actor
    class HotSwapActor extends Actor {
      import context._
      def angry: Receive = {
        case "foo" ⇒ sender ! "I am already angry?"
        case "bar" ⇒ become(happy)
      }

      def happy: Receive = {
        case "bar" ⇒ sender ! "I am already happy :-)"
        case "foo" ⇒ become(angry)
      }

      def receive = {
        case "foo" ⇒ become(angry)
        case "bar" ⇒ become(happy)
      }
    }
    //#hot-swap-actor

    val actor = system.actorOf(new HotSwapActor)

  }

}
