/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor

import language.postfixOps

//#imports1
import akka.actor.Actor
import akka.actor.Props
import akka.event.Logging

//#imports1

import scala.concurrent.Future
import akka.actor.{ ActorRef, ActorSystem }
import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.util._
import scala.concurrent.duration._
import akka.actor.Actor.Receive
import scala.concurrent.Await

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
  val child = context.actorOf(Props[MyActor], name = "myChild")
  //#plus-some-behavior
  def receive = {
    case x ⇒ sender ! x
  }
  //#plus-some-behavior
}
//#context-actorOf

class ActorWithArgs(arg: String) extends Actor {
  def receive = { case _ ⇒ () }
}

class DemoActorWrapper extends Actor {
  //#props-factory
  object DemoActor {
    /**
     * Create Props for an actor of this type.
     * @param name The name to be passed to this actor’s constructor.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    def apply(name: String): Props = Props(classOf[DemoActor], name)
  }

  class DemoActor(name: String) extends Actor {
    def receive = {
      case x ⇒ // some behavior
    }
  }

  // ...

  context.actorOf(DemoActor("hello"))
  //#props-factory

  def receive = Actor.emptyBehavior
}

class AnonymousActor extends Actor {
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
      })) forward m
  }
  //#anonymous-actor
}

class Hook extends Actor {
  var child: ActorRef = _
  //#preStart
  override def preStart() {
    child = context.actorOf(Props[MyActor], "child")
  }
  //#preStart
  def receive = Actor.emptyBehavior
  //#postStop
  override def postStop() {
    //#clean-up-some-resources
    ()
    //#clean-up-some-resources
  }
  //#postStop
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
      become({
        case Swap ⇒
          log.info("Ho")
          unbecome() // resets the latest 'become' (just for fun)
      }, discardOld = false) // push on top instead of replace
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
    new AnyRef {
      //#import-context
      class FirstActor extends Actor {
        import context._
        val myActor = actorOf(Props[MyActor], name = "myactor")
        def receive = {
          case x ⇒ myActor ! x
        }
      }
      //#import-context

      val first = system.actorOf(Props(classOf[FirstActor], this), name = "first")
      system.stop(first)
    }
  }

  "creating actor with system.actorOf" in {
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
    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    val myActor = system.actorOf(Props[MyActor], name = "myactor")
    //#creating-constructor

    system.stop(myActor)
  }

  "creating a Props config" in {
    //#creating-props
    import akka.actor.Props

    val props1 = Props[MyActor]
    val props3 = Props(classOf[ActorWithArgs], "arg")
    //#creating-props

    //#creating-props-deprecated
    // DEPRECATED: encourages to close over enclosing class
    val props4 = Props(
      creator = { () ⇒ new MyActor },
      dispatcher = "my-dispatcher")

    // DEPRECATED: encourages to close over enclosing class
    val props5 = props1.withCreator(new MyActor)

    // DEPRECATED: encourages to close over enclosing class
    val props6 = Props(new MyActor)

    // DEPRECATED due to duplicate functionality with Props.apply()
    val props7 = props1.withCreator(classOf[MyActor])
    //#creating-props-deprecated
  }

  "creating actor with Props" in {
    //#system-actorOf
    import akka.actor.ActorSystem

    // ActorSystem is a heavy object: create only one per application
    val system = ActorSystem("mySystem")
    val myActor = system.actorOf(Props[MyActor], "myactor2")
    //#system-actorOf
    shutdown(system)
  }

  "creating actor with IndirectActorProducer" in {
    class Echo(name: String) extends Actor {
      def receive = {
        case n: Int ⇒ sender ! name
        case message ⇒
          val target = testActor
          //#forward
          target forward message
        //#forward
      }
    }

    val a: { def actorRef: ActorRef } = new AnyRef {
      val applicationContext = this

      //#creating-indirectly
      import akka.actor.IndirectActorProducer

      class DependencyInjector(applicationContext: AnyRef, beanName: String)
        extends IndirectActorProducer {

        override def actorClass = classOf[Actor]
        override def produce =
          //#obtain-fresh-Actor-instance-from-DI-framework
          new Echo(beanName)

        def this(beanName: String) = this("", beanName)
        //#obtain-fresh-Actor-instance-from-DI-framework
      }

      val actorRef = system.actorOf(
        Props(classOf[DependencyInjector], applicationContext, "hello"),
        "helloBean")
      //#creating-indirectly
    }
    val actorRef = a.actorRef

    val message = 42
    implicit val self = testActor
    //#tell
    actorRef ! message
    //#tell
    expectMsg("hello")
    actorRef ! "huhu"
    expectMsg("huhu")
  }

  "using implicit timeout" in {
    val myActor = system.actorOf(Props[FirstActor])
    //#using-implicit-timeout
    import scala.concurrent.duration._
    import akka.util.Timeout
    import akka.pattern.ask
    implicit val timeout = Timeout(5 seconds)
    val future = myActor ? "hello"
    //#using-implicit-timeout
    Await.result(future, timeout.duration) must be("hello")

  }

  "using explicit timeout" in {
    val myActor = system.actorOf(Props[FirstActor])
    //#using-explicit-timeout
    import scala.concurrent.duration._
    import akka.pattern.ask
    val future = myActor.ask("hello")(5 seconds)
    //#using-explicit-timeout
    Await.result(future, 5 seconds) must be("hello")
  }

  "using receiveTimeout" in {
    //#receive-timeout
    import akka.actor.ReceiveTimeout
    import scala.concurrent.duration._
    class MyActor extends Actor {
      // To set an initial delay
      context.setReceiveTimeout(30 milliseconds)
      def receive = {
        case "Hello" ⇒
          // To set in a response to a message
          context.setReceiveTimeout(100 milliseconds)
        case ReceiveTimeout ⇒
          // To turn it off
          context.setReceiveTimeout(Duration.Undefined)
          throw new RuntimeException("Receive timed out")
      }
    }
    //#receive-timeout
  }

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

  "using hot-swap" in {
    val actor = system.actorOf(Props(classOf[HotSwapActor], this), name = "hot")
  }

  "using Stash" in {
    //#stash
    import akka.actor.Stash
    class ActorWithProtocol extends Actor with Stash {
      def receive = {
        case "open" ⇒
          unstashAll()
          context.become({
            case "write" ⇒ // do writing...
            case "close" ⇒
              unstashAll()
              context.unbecome()
            case msg ⇒ stash()
          }, discardOld = false) // stack on top instead of replacing
        case msg ⇒ stash()
      }
    }
    //#stash
  }

  "using watch" in {
    new AnyRef {
      //#watch
      import akka.actor.{ Actor, Props, Terminated }

      class WatchActor extends Actor {
        val child = context.actorOf(Props.empty, "child")
        context.watch(child) // <-- this is the only call needed for registration
        var lastSender = system.deadLetters

        def receive = {
          case "kill" ⇒
            context.stop(child); lastSender = sender
          case Terminated(`child`) ⇒ lastSender ! "finished"
        }
      }
      //#watch
      val a = system.actorOf(Props(classOf[WatchActor], this))
      implicit val sender = testActor
      a ! "kill"
      expectMsg("finished")
    }
  }

  "demonstrate ActorSelection" in {
    val context = system
    //#selection-local
    // will look up this absolute path
    context.actorSelection("/user/serviceA/aggregator")
    // will look up sibling beneath same supervisor
    context.actorSelection("../joe")
    //#selection-local
    //#selection-wildcard
    // will look all children to serviceB with names starting with worker
    context.actorSelection("/user/serviceB/worker*")
    // will look up all siblings beneath same supervisor
    context.actorSelection("../*")
    //#selection-wildcard
    //#selection-remote
    context.actorSelection("akka.tcp://app@otherhost:1234/user/serviceB")
    //#selection-remote
  }

  "using Identify" in {
    new AnyRef {
      //#identify
      import akka.actor.{ Actor, Props, Identify, ActorIdentity, Terminated }

      class Follower extends Actor {
        val identifyId = 1
        context.actorSelection("/user/another") ! Identify(identifyId)

        def receive = {
          case ActorIdentity(`identifyId`, Some(ref)) ⇒
            context.watch(ref)
            context.become(active(ref))
          case ActorIdentity(`identifyId`, None) ⇒ context.stop(self)

        }

        def active(another: ActorRef): Actor.Receive = {
          case Terminated(`another`) ⇒ context.stop(self)
        }
      }
      //#identify

      val a = system.actorOf(Props.empty)
      val b = system.actorOf(Props(classOf[Follower], this))
      watch(b)
      system.stop(a)
      expectMsgType[akka.actor.Terminated].actor must be === b
    }
  }

  "using pattern gracefulStop" in {
    val actorRef = system.actorOf(Props[MyActor])
    //#gracefulStop
    import akka.pattern.gracefulStop
    import scala.concurrent.Await

    try {
      val stopped: Future[Boolean] = gracefulStop(actorRef, 5 seconds)
      Await.result(stopped, 6 seconds)
      // the actor has been stopped
    } catch {
      // the actor wasn't stopped within 5 seconds
      case e: akka.pattern.AskTimeoutException ⇒
    }
    //#gracefulStop
  }

  "using pattern ask / pipeTo" in {
    val actorA, actorB, actorC, actorD = system.actorOf(Props.empty)
    //#ask-pipeTo
    import akka.pattern.{ ask, pipe }
    import system.dispatcher // The ExecutionContext that will be used
    case class Result(x: Int, s: String, d: Double)
    case object Request

    implicit val timeout = Timeout(5 seconds) // needed for `?` below

    val f: Future[Result] =
      for {
        x ← ask(actorA, Request).mapTo[Int] // call pattern directly
        s ← (actorB ask Request).mapTo[String] // call by implicit conversion
        d ← (actorC ? Request).mapTo[Double] // call by symbolic name
      } yield Result(x, s, d)

    f pipeTo actorD // .. or ..
    pipe(f) to actorD
    //#ask-pipeTo
  }

  class Replier extends Actor {
    def receive = {
      case ref: ActorRef ⇒
        //#reply-with-sender
        sender.tell("reply", context.parent) // replies will go back to parent
        sender.!("reply")(context.parent) // alternative syntax (beware of the parens!)
      //#reply-with-sender
      case x ⇒
        //#reply-without-sender
        sender ! x // replies will go to this actor
      //#reply-without-sender
    }
  }

  "replying with own or other sender" in {
    val actor = system.actorOf(Props(classOf[Replier], this))
    implicit val me = testActor
    actor ! 42
    expectMsg(42)
    lastSender must be === actor
    actor ! me
    expectMsg("reply")
    lastSender.path.elements.mkString("/", "/", "") must be === "/user"
    expectMsg("reply")
    lastSender.path.elements.mkString("/", "/", "") must be === "/user"
  }

  "using ActorDSL outside of akka.actor package" in {
    import akka.actor.ActorDSL._
    actor(new Act {
      superviseWith(OneForOneStrategy() { case _ ⇒ Stop; Restart; Resume; Escalate })
      superviseWith(AllForOneStrategy() { case _ ⇒ Stop; Restart; Resume; Escalate })
    })
  }

  //#receive-orElse2
  class PartialFunctionBuilder[A, B] {
    import scala.collection.immutable.Vector

    // Abbreviate to make code fit
    type PF = PartialFunction[A, B]

    private var pfsOption: Option[Vector[PF]] = Some(Vector.empty)

    private def mapPfs[C](f: Vector[PF] ⇒ (Option[Vector[PF]], C)): C = {
      pfsOption.fold(throw new IllegalStateException("Already built"))(f) match {
        case (newPfsOption, result) ⇒ {
          pfsOption = newPfsOption
          result
        }
      }
    }

    def +=(pf: PF): Unit =
      mapPfs { case pfs ⇒ (Some(pfs :+ pf), ()) }

    def result(): PF =
      mapPfs { case pfs ⇒ (None, pfs.foldLeft[PF](Map.empty) { _ orElse _ }) }
  }

  trait ComposableActor extends Actor {
    protected lazy val receiveBuilder = new PartialFunctionBuilder[Any, Unit]
    final def receive = receiveBuilder.result()
  }

  trait TheirComposableActor extends ComposableActor {
    receiveBuilder += {
      case "foo" ⇒ sender ! "foo received"
    }
  }

  class MyComposableActor extends TheirComposableActor {
    receiveBuilder += {
      case "bar" ⇒ sender ! "bar received"
    }
  }
  //#receive-orElse2

  "using ComposableActor" in {
    val composed = system.actorOf(Props(classOf[MyComposableActor], this))
    implicit val me = testActor
    composed ! "foo"
    expectMsg("foo received")
    composed ! "bar"
    expectMsg("bar received")
    EventFilter.warning(pattern = ".*unhandled message from.*baz", occurrences = 1) intercept {
      composed ! "baz"
    }
  }

}
