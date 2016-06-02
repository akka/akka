package akka.contrib.pattern

import akka.actor.{ Actor, Props }
import akka.persistence.{ PersistentActor }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._
import akka.testkit.TestProbe
import akka.actor.ActorLogging

object ReceivePipelineSpec {
  import ReceivePipeline._

  class ReplierActor extends Actor with ReceivePipeline {
    def receive: Actor.Receive = becomeAndReply
    def becomeAndReply: Actor.Receive = {
      case "become" ⇒ context.become(justReply)
      case m        ⇒ sender ! m
    }
    def justReply: Actor.Receive = {
      case m ⇒ sender ! m
    }
  }

  class IntReplierActor(max: Int) extends Actor with ReceivePipeline {
    def receive: Actor.Receive = {
      case m: Int if (m <= max) ⇒ sender ! m
    }
  }

  class TotallerActor extends Actor with ReceivePipeline {
    var total = 0
    def receive: Actor.Receive = {
      case m: Int ⇒ total += m
      case "get"  ⇒ sender ! total
    }
  }

  case class IntList(l: List[Int]) {
    override def toString: String = s"IntList(${l.mkString(", ")})"
  }

  trait ListBuilderInterceptor {
    this: ReceivePipeline ⇒

    pipelineOuter {
      case n: Int ⇒ Inner(IntList((n until n + 3).toList))
    }
  }

  trait AdderInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case n: Int               ⇒ Inner(n + 10)
      case IntList(l)           ⇒ Inner(IntList(l.map(_ + 10)))
      case "explicitly ignored" ⇒ HandledCompletely
    }
  }

  trait ToStringInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case i: Int             ⇒ Inner(i.toString)
      case IntList(l)         ⇒ Inner(l.toString)
      case other: Iterable[_] ⇒ Inner(other.toString)
    }
  }

  trait OddDoublerInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case i: Int if (i % 2 != 0) ⇒ Inner(i * 2)
    }
  }

  trait EvenHalverInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case i: Int if (i % 2 == 0) ⇒ Inner(i / 2)
    }
  }

  trait Timer {
    this: ReceivePipeline ⇒

    def notifyDuration(duration: Long): Unit

    pipelineInner {
      case msg: Any ⇒
        val start = 1L // = currentTimeMillis
        Inner(msg).andAfter {
          val end = 100L // = currentTimeMillis
          notifyDuration(end - start)
        }
    }
  }
}

class ReceivePipelineSpec extends AkkaSpec with ImplicitSender {
  import ReceivePipelineSpec._

  "A ReceivePipeline" must {

    "just invoke Actor's behavior when it's empty" in {
      val replier = system.actorOf(Props[ReplierActor])
      replier ! 3
      expectMsg(3)
    }

    "invoke decorated Actor's behavior when has one interceptor" in {
      val replier = system.actorOf(Props(new ReplierActor with AdderInterceptor))
      replier ! 5
      expectMsg(15)
    }

    "support any number of interceptors" in {
      val replier = system.actorOf(Props(
        new ReplierActor with ListBuilderInterceptor with AdderInterceptor with ToStringInterceptor))
      replier ! 8
      expectMsg("List(18, 19, 20)")
    }

    "delegate messages unhandled by interceptors to the inner behavior" in {

      val replier = system.actorOf(Props(
        new ReplierActor with ListBuilderInterceptor with AdderInterceptor with ToStringInterceptor))
      replier ! 8L // unhandled by all interceptors but still replied
      expectMsg(8L)
      replier ! Set(8F) // unhandled by all but ToString Interceptor, so replied as String
      expectMsg("Set(8.0)")
    }

    "let any interceptor to explicitly ignore some messages" in {

      val replier = system.actorOf(Props(
        new ReplierActor with ListBuilderInterceptor with AdderInterceptor with ToStringInterceptor))
      replier ! "explicitly ignored"
      replier ! 8L // unhandled by all interceptors but still replied
      expectMsg(8L)
    }

    "support changing behavior without losing the interceptions" in {
      val replier = system.actorOf(Props(
        new ReplierActor with ListBuilderInterceptor with AdderInterceptor with ToStringInterceptor))
      replier ! 8
      expectMsg("List(18, 19, 20)")
      replier ! "become"
      replier ! 3
      expectMsg("List(13, 14, 15)")
    }

    "support swapping inner and outer interceptors mixin order" in {
      val outerInnerReplier = system.actorOf(Props(
        new ReplierActor with ListBuilderInterceptor with AdderInterceptor))
      val innerOuterReplier = system.actorOf(Props(
        new ReplierActor with AdderInterceptor with ListBuilderInterceptor))
      outerInnerReplier ! 4
      expectMsg(IntList(List(14, 15, 16)))
      innerOuterReplier ! 6
      expectMsg(IntList(List(16, 17, 18)))
    }
  }

}

object PersistentReceivePipelineSpec {
  class PersistentReplierActor extends PersistentActor with ReceivePipeline {
    override def persistenceId: String = "p-1"

    def becomeAndReply: Actor.Receive = {
      case "become" ⇒ context.become(justReply)
      case m        ⇒ sender ! m
    }
    def justReply: Actor.Receive = {
      case m ⇒ sender ! m
    }

    override def receiveCommand: Receive = becomeAndReply
    override def receiveRecover: Receive = {
      case _ ⇒ // ...
    }
  }

}
class PersistentReceivePipelineSpec(config: Config) extends AkkaSpec(config) with ImplicitSender {
  import ReceivePipelineSpec._
  import PersistentReceivePipelineSpec._

  def this() {
    this(ConfigFactory.parseString(
      s"""
        |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        |akka.persistence.journal.leveldb.dir = "target/journal-${getClass.getSimpleName}"
      """.stripMargin))
  }

  "A PersistentActor with ReceivePipeline" must {
    "support any number of interceptors" in {
      val replier = system.actorOf(Props(
        new PersistentReplierActor with ListBuilderInterceptor with AdderInterceptor with ToStringInterceptor))
      replier ! 8
      expectMsg("List(18, 19, 20)")
    }
    "allow messages explicitly passed on by interceptors to be handled by the actor" in {
      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor))

      // 6 -> 3 -> 6
      replier ! 6
      expectMsg(6)
    }

    "allow messages not handled by some interceptors to be handled by the actor" in {
      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor))

      // 8 -> 4 ( -> not handled by OddDoublerInterceptor)
      replier ! 8
      expectMsg(4)
    }

    "allow messages explicitly passed on by interceptors but not handled by the actor to be treated as unhandled" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor {
          override def unhandled(message: Any) = probeRef ! message
        }))

      // 22 -> 11 -> 22 but > 10 so not handled in main receive: falls back to unhandled implementation...
      replier ! 22
      probe.expectMsg(22)
    }

    "allow messages not handled by some interceptors or by the actor to be treated as unhandled" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor {
          override def unhandled(message: Any) = probeRef ! message
        }))

      // 11 ( -> not handled by EvenHalverInterceptor) -> 22 but > 10 so not handled in main receive: 
      // original message falls back to unhandled implementation...
      replier ! 11
      probe.expectMsg(11)
    }

    "allow messages not handled by any interceptors or by the actor to be treated as unhandled" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor {
          override def unhandled(message: Any) = probeRef ! message
        }))

      replier ! "hi there!"
      probe.expectMsg("hi there!")
    }

    "not treat messages handled by the actor as unhandled" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor {
          override def unhandled(message: Any) = probeRef ! message
        }))

      replier ! 4
      expectMsg(2)
      probe.expectNoMsg(100.millis)
    }

    "continue to handle messages normally after unhandled messages" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val replier = system.actorOf(Props(
        new IntReplierActor(10) with EvenHalverInterceptor with OddDoublerInterceptor {
          override def unhandled(message: Any) = probeRef ! message
        }))

      replier ! "hi there!"
      replier ! 8
      probe.expectMsg("hi there!")
      expectMsg(4)
    }

    "call side-effecting receive code only once" in {
      val totaller = system.actorOf(Props(
        new TotallerActor with EvenHalverInterceptor with OddDoublerInterceptor))

      totaller ! 8
      totaller ! 6
      totaller ! "get"
      expectMsg(10)
    }

    "not cache the result of the same message" in {
      val totaller = system.actorOf(Props(
        new TotallerActor with EvenHalverInterceptor with OddDoublerInterceptor))

      totaller ! 6
      totaller ! 6
      totaller ! "get"
      expectMsg(12)
    }

    "run code in 'after' block" in {
      val probe = new TestProbe(system)
      val probeRef = probe.ref

      val totaller = system.actorOf(Props(
        new TotallerActor with Timer {
          def notifyDuration(d: Long) = probeRef ! d
        }))

      totaller ! 6
      totaller ! "get"
      expectMsg(6)
      probe.expectMsg(99)
    }
  }
}

// Just compiling code samples for documentation. Not intended to be tests.

object InActorSample extends App {
  import ReceivePipeline._

  import akka.actor.ActorSystem

  val system = ActorSystem("pipeline")

  val actor = system.actorOf(Props[PipelinedActor]())

  //#in-actor
  class PipelinedActor extends Actor with ReceivePipeline {

    // Increment
    pipelineInner { case i: Int ⇒ Inner(i + 1) }
    // Double
    pipelineInner { case i: Int ⇒ Inner(i * 2) }

    def receive: Receive = { case any ⇒ println(any) }
  }

  actor ! 5 // prints 12 = (5 + 1) * 2
  //#in-actor

  val withOuterActor = system.actorOf(Props[PipelinedOuterActor]())

  class PipelinedOuterActor extends Actor with ReceivePipeline {

    //#in-actor-outer
    // Increment
    pipelineInner { case i: Int ⇒ Inner(i + 1) }
    // Double
    pipelineOuter { case i: Int ⇒ Inner(i * 2) }

    // prints 11 = (5 * 2) + 1
    //#in-actor-outer

    def receive: Receive = { case any ⇒ println(any) }
  }

  withOuterActor ! 5

}

object InterceptorSamples {
  import ReceivePipeline._

  //#interceptor-sample1
  val incrementInterceptor: Interceptor = {
    case i: Int ⇒ Inner(i + 1)
  }
  //#interceptor-sample1

  def logTimeTaken(time: Long) = ???

  //#interceptor-sample2
  val timerInterceptor: Interceptor = {
    case e ⇒
      val start = System.nanoTime
      Inner(e).andAfter {
        val end = System.nanoTime
        logTimeTaken(end - start)
      }
  }
  //#interceptor-sample2

}

object MixinSample extends App {
  import ReceivePipeline._

  import akka.actor.{ ActorSystem, Props }

  val system = ActorSystem("pipeline")

  //#mixin-model
  val texts = Map(
    "that.rug_EN" → "That rug really tied the room together.",
    "your.opinion_EN" → "Yeah, well, you know, that's just, like, your opinion, man.",
    "that.rug_ES" → "Esa alfombra realmente completaba la sala.",
    "your.opinion_ES" → "Sí, bueno, ya sabes, eso es solo, como, tu opinion, amigo.")

  case class I18nText(locale: String, key: String)
  case class Message(author: Option[String], text: Any)
  //#mixin-model

  //#mixin-interceptors
  trait I18nInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case m @ Message(_, I18nText(loc, key)) ⇒
        Inner(m.copy(text = texts(s"${key}_$loc")))
    }
  }

  trait AuditInterceptor {
    this: ReceivePipeline ⇒

    pipelineOuter {
      case m @ Message(Some(author), text) ⇒
        println(s"$author is about to say: $text")
        Inner(m)
    }
  }
  //#mixin-interceptors

  val printerActor = system.actorOf(Props[PrinterActor]())

  //#mixin-actor
  class PrinterActor extends Actor with ReceivePipeline
    with I18nInterceptor with AuditInterceptor {

    override def receive: Receive = {
      case Message(author, text) ⇒
        println(s"${author.getOrElse("Unknown")} says '$text'")
    }
  }

  printerActor ! Message(Some("The Dude"), I18nText("EN", "that.rug"))
  // The Dude is about to say: I18nText(EN,that.rug)
  // The Dude says 'That rug really tied the room together.'

  printerActor ! Message(Some("The Dude"), I18nText("EN", "your.opinion"))
  // The Dude is about to say: I18nText(EN,your.opinion)
  // The Dude says 'Yeah, well, you know, that's just, like, your opinion, man.'
  //#mixin-actor

  system.terminate()
}

object UnhandledSample extends App {
  import ReceivePipeline._

  def isGranted(userId: Long) = true

  //#unhandled
  case class PrivateMessage(userId: Option[Long], msg: Any)

  trait PrivateInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner {
      case PrivateMessage(Some(userId), msg) ⇒
        if (isGranted(userId))
          Inner(msg)
        else
          HandledCompletely
    }
  }
  //#unhandled

}

object AfterSamples {
  import ReceivePipeline._

  //#interceptor-after
  trait TimerInterceptor extends ActorLogging {
    this: ReceivePipeline ⇒

    def logTimeTaken(time: Long) = log.debug(s"Time taken: $time ns")

    pipelineOuter {
      case e ⇒
        val start = System.nanoTime
        Inner(e).andAfter {
          val end = System.nanoTime
          logTimeTaken(end - start)
        }
    }
  }
  //#interceptor-after
}