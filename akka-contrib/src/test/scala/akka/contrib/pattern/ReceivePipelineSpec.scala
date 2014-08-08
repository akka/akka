package akka.contrib.pattern

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.{ Actor, Props }

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

trait ListBuilderInterceptor {
  this: ReceivePipeline ⇒

  pipelineOuter(inner ⇒
    {
      case n: Int ⇒ inner((n until n + 3).toList)
    })
}

trait AdderInterceptor {
  this: ReceivePipeline ⇒

  pipelineInner(inner ⇒
    {
      case n: Int               ⇒ inner(n + 10)
      case l: List[Int]         ⇒ inner(l.map(_ + 10))
      case "explicitly ignored" ⇒
    })
}

trait ToStringInterceptor {
  this: ReceivePipeline ⇒

  pipelineInner(inner ⇒
    {
      case i: Int         ⇒ inner(i.toString)
      case l: Iterable[_] ⇒ inner(l.toString())
    })
}

class ReceivePipelineSpec extends AkkaSpec with ImplicitSender {

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
      expectNoMsg()
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
      expectMsg(List(14, 15, 16))
      innerOuterReplier ! 6
      expectMsg(List(16, 17, 18))
    }

  }

}

// Just compiling code samples for documentation. Not intended to be tests.

object InterceptorSample extends App {

  import Actor.Receive

  //#interceptor
  val printReceive: Receive = { case any ⇒ println(any) }

  val incrementDecorator: Receive ⇒ Receive =
    inner ⇒ { case i: Int ⇒ inner(i + 1) }

  val incPrint = incrementDecorator(printReceive)

  incPrint(10) // prints 11
  //#interceptor
}

object InActorSample extends App {

  import akka.actor.ActorSystem

  val system = ActorSystem("pipeline")

  val actor = system.actorOf(Props[PipelinedActor]())

  //#in-actor
  class PipelinedActor extends Actor with ReceivePipeline {

    // Increment
    pipelineInner(inner ⇒ { case i: Int ⇒ inner(i + 1) })
    // Double
    pipelineInner(inner ⇒ { case i: Int ⇒ inner(i * 2) })

    def receive: Receive = { case any ⇒ println(any) }
  }

  actor ! 5 // prints 12 = (5 + 1) * 2
  //#in-actor

  val withOuterActor = system.actorOf(Props[PipelinedOuterActor]())

  class PipelinedOuterActor extends Actor with ReceivePipeline {

    //#in-actor-outer
    // Increment
    pipelineInner(inner ⇒ { case i: Int ⇒ inner(i + 1) })
    // Double
    pipelineOuter(inner ⇒ { case i: Int ⇒ inner(i * 2) })

    // prints 11 = (5 * 2) + 1
    //#in-actor-outer

    def receive: Receive = { case any ⇒ println(any) }
  }

  withOuterActor ! 5

}

object MixinSample extends App {

  import akka.actor.{ ActorSystem, Props }

  val system = ActorSystem("pipeline")

  //#mixin-model
  val texts = Map(
    "that.rug_EN" -> "That rug really tied the room together.",
    "your.opinion_EN" -> "Yeah, well, you know, that's just, like, your opinion, man.",
    "that.rug_ES" -> "Esa alfombra realmente completaba la sala.",
    "your.opinion_ES" -> "Sí, bueno, ya sabes, eso es solo, como, tu opinion, amigo.")

  case class I18nText(locale: String, key: String)
  case class Message(author: Option[String], text: Any)
  //#mixin-model

  //#mixin-interceptors
  trait I18nInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner(
      inner ⇒ {
        case m @ Message(_, I18nText(loc, key)) ⇒
          inner(m.copy(text = texts(s"${key}_$loc")))
      })
  }

  trait AuditInterceptor {
    this: ReceivePipeline ⇒

    pipelineOuter(
      inner ⇒ {
        case m @ Message(Some(author), text) ⇒
          println(s"$author is about to say: $text")
          inner(m)
      })
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

  system.shutdown()
}

object UnhandledSample extends App {

  def isGranted(userId: Long) = true

  //#unhandled
  case class PrivateMessage(userId: Option[Long], msg: Any)

  trait PrivateInterceptor {
    this: ReceivePipeline ⇒

    pipelineInner(
      inner ⇒ {
        case PrivateMessage(Some(userId), msg) if isGranted(userId) ⇒ inner(msg)
        case _ ⇒
      })
  }
  //#unhandled

}