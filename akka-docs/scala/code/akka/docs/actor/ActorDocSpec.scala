/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

//#imports1
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging

//#imports1

import akka.dispatch.Future
import akka.actor.ActorSystem
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.util._
import akka.util.duration._
import akka.actor.Actor.Receive
import akka.dispatch.Await

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
  val myActor = context.actorOf(Props[MyActor], name = "myactor")
  //#context-actorOf
  //#anonymous-actor
  def receive = {
    case m: DoIt ⇒
      context.actorOf(Props(new Actor {
        def receive = {
          case DoIt(msg) ⇒
            val replyMsg = doSomeDangerousWork(msg)
            sender ! replyMsg
            context.stop(self)
        }
        def doSomeDangerousWork(msg: ImmutableMessage): String = { "done" }
      })) ! m

    case replyMsg: String ⇒ sender ! replyMsg
  }
  //#anonymous-actor
}

//#system-actorOf
object Main extends App {
  val system = ActorSystem("MySystem")
  val myActor = system.actorOf(Props[MyActor], name = "myactor")
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
  val swap = system.actorOf(Props[Swapper], name = "swapper")
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
  swap ! Swap // logs Hi
  swap ! Swap // logs Ho
}
//#swapper

//#receive-orElse

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
      val myActor = actorOf(Props[MyActor], name = "myactor")
      def receive = {
        case x ⇒ myActor ! x
      }
    }
    //#import-context

    val first = system.actorOf(Props(new FirstActor), name = "first")
    system.stop(first)

  }

  "creating actor with AkkaSpec.actorOf" in {
    val myActor = system.actorOf(Props[MyActor])

    // testing the actor

    // TODO: convert docs to AkkaSpec(Map(...))
    val filter = EventFilter.custom {
      case e: Logging.Info ⇒ true
      case _               ⇒ false
    }
    system.eventStream.publish(TestEvent.Mute(filter))
    system.eventStream.subscribe(testActor, classOf[Logging.Info])

    myActor ! "test"
    expectMsgPF(1 second) { case Logging.Info(_, _, "received test") ⇒ true }

    myActor ! "unknown"
    expectMsgPF(1 second) { case Logging.Info(_, _, "received unknown message") ⇒ true }

    system.eventStream.unsubscribe(testActor)
    system.eventStream.publish(TestEvent.UnMute(filter))

    system.stop(myActor)
  }

  "creating actor with constructor" in {
    class MyActor(arg: String) extends Actor {
      def receive = { case _ ⇒ () }
    }

    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = system.actorOf(Props(new MyActor("...")), name = "myactor")
    //#creating-constructor

    system.stop(myActor)
  }

  "creating a Props config" in {
    //#creating-props-config
    import akka.actor.Props
    val props1 = Props()
    val props2 = Props[MyActor]
    val props3 = Props(new MyActor)
    val props4 = Props(
      creator = { () ⇒ new MyActor },
      dispatcher = "my-dispatcher")
    val props5 = props1.withCreator(new MyActor)
    val props6 = props5.withDispatcher("my-dispatcher")
    //#creating-props-config
  }

  "creating actor with Props" in {
    //#creating-props
    import akka.actor.Props
    val myActor = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), name = "myactor")
    //#creating-props

    system.stop(myActor)
  }

  "using ask" in {
    //#using-ask
    import akka.pattern.ask

    class MyActor extends Actor {
      def receive = {
        case x: String ⇒ sender ! x.toUpperCase
        case n: Int    ⇒ sender ! (n + 1)
      }
    }

    val myActor = system.actorOf(Props(new MyActor), name = "myactor")
    implicit val timeout = system.settings.ActorTimeout
    val future = ask(myActor, "hello")
    for (x ← future) println(x) //Prints "hello"

    val result: Future[Int] = for (x ← ask(myActor, 3).mapTo[Int]) yield { 2 * x }
    //#using-ask

    system.stop(myActor)
  }

  "using implicit timeout" in {
    val myActor = system.actorOf(Props(new FirstActor))
    //#using-implicit-timeout
    import akka.util.duration._
    import akka.util.Timeout
    import akka.pattern.ask
    implicit val timeout = Timeout(500 millis)
    val future = myActor ? "hello"
    //#using-implicit-timeout
    Await.result(future, timeout.duration) must be("hello")

  }

  "using explicit timeout" in {
    val myActor = system.actorOf(Props(new FirstActor))
    //#using-explicit-timeout
    import akka.util.duration._
    import akka.pattern.ask
    val future = myActor ? ("hello", timeout = 500 millis)
    //#using-explicit-timeout
    Await.result(future, 500 millis) must be("hello")
  }

  "using receiveTimeout" in {
    //#receive-timeout
    import akka.actor.ReceiveTimeout
    import akka.util.duration._
    class MyActor extends Actor {
      context.setReceiveTimeout(30 milliseconds)
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

    val actor = system.actorOf(Props(new HotSwapActor), name = "hot")
  }

  "using watch" in {
    //#watch
    import akka.actor.{ Actor, Props, Terminated }

    class WatchActor extends Actor {
      val child = context.actorOf(Props.empty, "child")
      context.watch(child) // <-- this is the only call needed for registration
      var lastSender = system.deadLetters

      def receive = {
        case "kill"              ⇒ context.stop(child); lastSender = sender
        case Terminated(`child`) ⇒ lastSender ! "finished"
      }
    }
    //#watch
    val a = system.actorOf(Props(new WatchActor))
    implicit val sender = testActor
    a ! "kill"
    expectMsg("finished")
  }

  "using pattern gracefulStop" in {
    val actorRef = system.actorOf(Props[MyActor])
    //#gracefulStop
    import akka.pattern.gracefulStop
    import akka.dispatch.Await
    import akka.actor.ActorTimeoutException

    try {
      val stopped: Future[Boolean] = gracefulStop(actorRef, 5 seconds)(system)
      Await.result(stopped, 6 seconds)
      // the actor has been stopped
    } catch {
      case e: ActorTimeoutException ⇒ // the actor wasn't stopped within 5 seconds
    }
    //#gracefulStop

  }
}
