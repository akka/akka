/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

object LoggingDocExamples {

  object BackendManager {
    sealed trait Command {
      def identifier: String
    }
    def apply(): Behavior[Command] = Behaviors.empty
  }

  def howToUse(): Unit = {

    //#context-log
    Behaviors.receive[String] { (context, message) =>
      context.log.info("Received message: {}", message)
      Behaviors.same
    }
    //#context-log

    //#logger-name
    Behaviors.setup[String] { context =>
      context.setLoggerName("com.myservice.BackendManager")
      context.log.info("Starting up")

      Behaviors.receiveMessage { message =>
        context.log.debug("Received message: {}", message)
        Behaviors.same
      }
    }
    //#logger-name

    //#logger-factory
    val log = LoggerFactory.getLogger("com.myservice.BackendTask")

    Future {
      // some work
      "result"
    }.onComplete {
      case Success(result) => log.info("Task completed: {}", result)
      case Failure(exc)    => log.error("Task failed", exc)
    }
    //#logger-factory

  }

  def placeholders(): Unit = {
    //#info2
    import akka.actor.typed.scaladsl.LoggerOps

    Behaviors.receive[String] { (context, message) =>
      context.log.info2("{} received message: {}", context.self.path.name, message)
      Behaviors.same
    }
    //#info2

    //#infoN
    import akka.actor.typed.scaladsl.LoggerOps

    Behaviors.receive[String] { (context, message) =>
      context.log.infoN(
        "{} received message of size {} starting with: {}",
        context.self.path.name,
        message.length,
        message.take(10))
      Behaviors.same
    }
    //#infoN

  }

  def logMessages(): Unit = {
    //#logMessages
    import akka.actor.typed.LogOptions
    import org.slf4j.event.Level

    Behaviors.logMessages(LogOptions().withLevel(Level.TRACE), BackendManager())
    //#logMessages
  }

  def withMdc(): Unit = {
    val system: ActorSystem[_] = ???

    //#withMdc
    val staticMdc = Map("startTime" -> system.startTime.toString)
    Behaviors.withMdc[BackendManager.Command](
      staticMdc,
      mdcForMessage = msg => Map("identifier" -> msg.identifier, "upTime" -> system.uptime.toString)) {
      BackendManager()
    }
    //#withMdc
  }

}
