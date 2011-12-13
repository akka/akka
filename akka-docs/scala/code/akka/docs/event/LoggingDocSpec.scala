package akka.docs.event

import akka.actor.ActorSystem
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
      case "test" ⇒ log.info("Received test")
      case x      ⇒ log.warning("Received unknown message: {}", x)
    }
  }
  //#my-actor

  //#my-event-listener
  import akka.event.Logging.InitializeLogger
  import akka.event.Logging.LoggerInitialized
  import akka.event.Logging.Error
  import akka.event.Logging.Warning
  import akka.event.Logging.Info
  import akka.event.Logging.Debug

  class MyEventListener extends Actor {
    def receive = {
      case InitializeLogger(_)              ⇒ sender ! LoggerInitialized
      case Error(cause, logSource, message) ⇒ // ...
      case Warning(logSource, message)      ⇒ // ...
      case Info(logSource, message)         ⇒ // ...
      case Debug(logSource, message)        ⇒ // ...
    }
  }
  //#my-event-listener

}

class LoggingDocSpec extends AkkaSpec {

  import LoggingDocSpec.MyActor

  "use a logging actor" in {
    val myActor = system.actorOf(Props(new MyActor))
    myActor ! "test"
  }

}
