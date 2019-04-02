/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.typed

//#import
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
//#import

object PingPongExample {
  //#ping-service
  val PingServiceKey = ServiceKey[Ping]("pingService")

  final case class Ping(replyTo: ActorRef[Pong.type])
  final case object Pong

  val pingService: Behavior[Ping] =
    Behaviors.setup { ctx =>
      ctx.system.receptionist ! Receptionist.Register(PingServiceKey, ctx.self)
      Behaviors.receive { (context, msg) =>
        msg match {
          case Ping(replyTo) =>
            context.log.info("Pinged by {}", replyTo)
            replyTo ! Pong
            Behaviors.same
        }
      }
    }
  //#ping-service

  //#pinger
  def pinger(pingService: ActorRef[Ping]): Behavior[Pong.type] =
    Behaviors.setup[Pong.type] { ctx =>
      pingService ! Ping(ctx.self)
      Behaviors.receive { (context, _) =>
        context.log.info("{} was ponged!!", context.self)
        Behaviors.stopped
      }
    }
  //#pinger

  //#pinger-guardian
  val guardian: Behavior[Nothing] =
    Behaviors
      .setup[Listing] { context =>
        context.spawnAnonymous(pingService)
        context.system.receptionist ! Receptionist.Subscribe(PingServiceKey, context.self)
        Behaviors.receiveMessagePartial[Listing] {
          case PingServiceKey.Listing(listings) =>
            listings.foreach(ps => context.spawnAnonymous(pinger(ps)))
            Behaviors.same
        }
      }
      .narrow
  //#pinger-guardian

}

object ReceptionistExample {
  import akka.actor.typed.ActorSystem

  def main(args: Array[String]): Unit = {
    val system = ActorSystem[Nothing](PingPongExample.guardian, "PingPongExample")
    Thread.sleep(10000)
    system.terminate()
  }

}
