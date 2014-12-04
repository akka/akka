/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.event

import akka.actor.AllDeadLetters
import akka.testkit.AkkaSpec
import akka.actor.Actor
import akka.actor.Props

object LoggingDocSpec {

  //#my-actor
  import akka.event.Logging

  class MyActor extends Actor {
    val log = Logging(context.system, this)
    override def preStart() = {
      log.debug("Starting")
    }
    override def preRestart(reason: Throwable, message: Option[Any]) {
      log.error(reason, "Restarting due to [{}] when processing [{}]",
        reason.getMessage, message.getOrElse(""))
    }
    def receive = {
      case "test" => log.info("Received test")
      case x      => log.warning("Received unknown message: {}", x)
    }
  }
  //#my-actor

  import akka.event.Logging

  class MdcActor extends Actor {
    val log = Logging(this)
    def receive = {

      case _ => {
        //#mdc
        val mdc = Map("requestId" -> 1234, "visitorId" -> 5678)
        log.mdc(mdc)

        // Log something
        log.info("Starting new request")

        log.clearMDC()
        //#mdc
      }
    }
  }

  //#mdc-actor
  import Logging.MDC

  final case class Req(work: String, visitorId: Int)

  class MdcActorMixin extends Actor with akka.actor.DiagnosticActorLogging {
    var reqId = 0

    override def mdc(currentMessage: Any): MDC = {
      reqId += 1
      val always = Map("requestId" -> reqId)
      val perMessage = currentMessage match {
        case r: Req => Map("visitorId" -> r.visitorId)
        case _      => Map()
      }
      always ++ perMessage
    }

    def receive: Receive = {
      case r: Req => {
        log.info(s"Starting new request: ${r.work}")
      }
    }
  }

  //#mdc-actor

  //#my-event-listener
  import akka.event.Logging.InitializeLogger
  import akka.event.Logging.LoggerInitialized
  import akka.event.Logging.Error
  import akka.event.Logging.Warning
  import akka.event.Logging.Info
  import akka.event.Logging.Debug

  class MyEventListener extends Actor {
    def receive = {
      case InitializeLogger(_)                        => sender() ! LoggerInitialized
      case Error(cause, logSource, logClass, message) => // ...
      case Warning(logSource, logClass, message)      => // ...
      case Info(logSource, logClass, message)         => // ...
      case Debug(logSource, logClass, message)        => // ...
    }
  }
  //#my-event-listener

  //#my-source
  import akka.event.LogSource
  import akka.actor.ActorSystem

  object MyType {
    implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
      def genString(o: AnyRef): String = o.getClass.getName
      override def getClazz(o: AnyRef): Class[_] = o.getClass
    }
  }

  class MyType(system: ActorSystem) {
    import MyType._
    import akka.event.Logging

    val log = Logging(system, this)
  }
  //#my-source
}

class LoggingDocSpec extends AkkaSpec {

  import LoggingDocSpec.{ MyActor, MdcActor, MdcActorMixin, Req }

  "use a logging actor" in {
    val myActor = system.actorOf(Props[MyActor])
    myActor ! "test"
  }

  "use a MDC logging actor" in {
    val mdcActor = system.actorOf(Props[MdcActor])
    mdcActor ! "some request"
  }

  "use a MDC logging actor by mixin" in {
    val mdcActor = system.actorOf(Props[MdcActorMixin])
    mdcActor ! Req("some request", 5678)
  }

  "allow registration to dead letters" in {
    new AnyRef {
      //#deadletters
      import akka.actor.{ Actor, DeadLetter, Props }

      class Listener extends Actor {
        def receive = {
          case d: DeadLetter => println(d)
        }
      }
      val listener = system.actorOf(Props(classOf[Listener], this))
      system.eventStream.subscribe(listener, classOf[DeadLetter])
      //#deadletters
    }
  }

  "allow registration to suppressed dead letters" in {
    new AnyRef {
      import akka.actor.Props
      val listener = system.actorOf(Props[MyActor])

      //#suppressed-deadletters
      import akka.actor.SuppressedDeadLetter
      system.eventStream.subscribe(listener, classOf[SuppressedDeadLetter])
      //#suppressed-deadletters

      //#all-deadletters
      import akka.actor.AllDeadLetters
      system.eventStream.subscribe(listener, classOf[AllDeadLetters])
      //#all-deadletters
    }
  }

  "demonstrate logging more arguments" in {
    //#array
    val args = Array("The", "brown", "fox", "jumps", 42)
    system.log.debug("five parameters: {}, {}, {}, {}, {}", args)
    //#array
  }

}
