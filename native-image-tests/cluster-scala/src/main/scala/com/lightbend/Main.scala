package com.lightbend

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.serialization.jackson.CborSerializable
import akka.serialization.jackson.JsonSerializable

final case class Response(message: String) extends CborSerializable

object PingPong {
  val serviceKey = ServiceKey[PingPong.Ping]("PingPong")

  final case class Ping(replyTo: ActorRef[Response]) extends JsonSerializable

  def apply(): Behavior[Ping] = Behaviors.receive { (context, message) =>
    context.log.info("Saw ping")
    message.replyTo ! Response("Typed actor reply")
    Behaviors.same
  }
}

object RootBehavior {
  def apply(): Behavior[Response] = Behaviors.setup { context =>
    val localPingPong = context.spawn(PingPong(), "PingPong")
    context.system.receptionist ! Receptionist.Register(PingPong.serviceKey, localPingPong)

    Behaviors.empty
  }
}

object Main {

  def main(args: Array[String]): Unit = {
    ActorSystem(RootBehavior(), "AkkaNativeClusterTest")
  }

}
