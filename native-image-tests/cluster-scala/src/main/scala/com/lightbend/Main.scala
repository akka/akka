package com.lightbend

import akka.actor.Address
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Routers
import akka.serialization.jackson.CborSerializable
import akka.serialization.jackson.JsonSerializable
import akka.util.Timeout
import com.fasterxml.jackson.annotation.JsonCreator

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

final case class Response(message: String, address: Address) extends CborSerializable

object PingPong {
  val serviceKey = ServiceKey[PingPong.Ping]("PingPong")

  final case class Ping(replyTo: ActorRef[Response], from: Address) extends JsonSerializable

  def apply(): Behavior[Ping] = Behaviors.receive { (context, message) =>
    context.log.debug("Saw ping from {}", message.from)
    message.replyTo ! Response("Typed actor reply", context.system.address)
    Behaviors.same
  }
}

object PingEmAll {
  def apply(whenDone: ActorRef[String], expectedNodeCount: Int): Behavior[AnyRef] = Behaviors.setup { context =>
    val router = context.spawn(Routers.group(PingPong.serviceKey), "PingPongRouter")

    Behaviors.withTimers { timers =>
      implicit val timeout: Timeout = 3.seconds
      timers.startTimerWithFixedDelay("Tick", 300.millis)
      context.self ! "Tick"

      var seenAddresses = Set[Address]()

      Behaviors.receiveMessage {
        case "Tick" =>
          context.ask(router, PingPong.Ping(_, context.system.address)) {
            case Success(value)     => value
            case Failure(exception) => exception.getMessage
          }
          Behaviors.same

        case Response(message, address) =>
          context.log.debug("Got response {} from address {}", message, address)
          seenAddresses += address
          if (seenAddresses.size == expectedNodeCount) {
            context.log.debug("Saw responses from all nodes, shutting down")
            whenDone ! "Pinged all nodes and saw responses"
            Behaviors.stopped
          } else {
            Behaviors.same
          }

        case error: String =>
          // probably a timeout
          context.log.debug("Saw error {}", error)
          Behaviors.same

      }

    }
  }
}

object RootBehavior {
  def apply(): Behavior[AnyRef] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      timers.startSingleTimer("Timeout", 30.seconds)

      val localPingPong = context.spawn(PingPong(), "PingPong")
      context.system.receptionist ! Receptionist.Register(PingPong.serviceKey, localPingPong)
      context.spawn(PingEmAll(context.self, 2), "PingEmAll")

      var expectedResponses = Set("Pinged all nodes and saw responses")

      Behaviors.receiveMessage {
        case "Timeout" =>
          context.log.error("Timed out with expected responses left: {}", expectedResponses.mkString(", "))
          System.exit(1)
          Behaviors.stopped

        case "Stop" =>
          context.log.info("Successfully completed, stopping")
          Behaviors.stopped

        case message: String =>
          expectedResponses -= message
          if (expectedResponses.isEmpty) {
            // Note delay so that the other node also can complete
            context.log.info("All checks completed, stopping in 10s")
            timers.startSingleTimer("Stop", 10.seconds)
            Behaviors.same
          } else {
            Behaviors.same
          }
        case other =>
          context.log.error("Unexpected message {}", other)
          Behaviors.stopped
      }
    }
  }
}

object Main {

  def main(args: Array[String]): Unit = {
    ActorSystem(RootBehavior(), "AkkaNativeClusterTest")
  }

}
