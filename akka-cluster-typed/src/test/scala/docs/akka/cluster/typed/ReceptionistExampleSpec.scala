/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.typed

import akka.actor.typed._
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.receptionist.Receptionist.Listing
import akka.actor.typed.scaladsl._
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

object PingPongExample {
  //#ping-service
  val PingServiceKey = ServiceKey[Ping]("pingService")

  final case class Ping(replyTo: ActorRef[Pong.type])
  final case object Pong

  val pingService: Behavior[Ping] =
    Behaviors.setup { ctx ⇒
      ctx.system.receptionist ! Receptionist.Register(PingServiceKey, ctx.self)
      Behaviors.receive[Ping] { (_, msg) ⇒
        msg match {
          case Ping(replyTo) ⇒
            println("Pinged, now stopping")
            replyTo ! Pong
            Behaviors.stopped
        }
      }
    }
  //#ping-service

  //#pinger
  def pinger(pingService: ActorRef[Ping]) = Behaviors.setup[Pong.type] { ctx ⇒
    pingService ! Ping(ctx.self)
    Behaviors.receive { (_, msg) ⇒
      println("I was ponged!!" + msg)
      Behaviors.same
    }
  }
  //#pinger

  //#pinger-guardian
  val guardian: Behavior[Nothing] = Behaviors.setup[Listing] { ctx ⇒
    ctx.system.receptionist ! Receptionist.Subscribe(PingServiceKey, ctx.self)
    val ps = ctx.spawnAnonymous(pingService)
    ctx.watch(ps)
    Behaviors.receiveMessagePartial[Listing] {
      case PingServiceKey.Listing(listings) if listings.nonEmpty ⇒
        listings.foreach(ps ⇒ ctx.spawnAnonymous(pinger(ps)))
        Behaviors.same
    } receiveSignal {
      case (_, Terminated(`ps`)) ⇒
        println("Ping service has shut down")
        Behaviors.stopped
    }
  }.narrow
  //#pinger-guardian

}

object ReceptionistExampleSpec {
  val clusterConfig = ConfigFactory.parseString(
    s"""
#config
akka {
  actor {
    provider = "cluster"
  }
  cluster.jmx.multi-mbeans-in-same-jvm = on
  remote {
    netty.tcp {
      hostname = "127.0.0.1"
      port = 2551
    }
  }
}
#config
akka.remote.netty.tcp.port = 0
akka.remote.artery.canonical.port = 0
     """)

}

class ReceptionistExampleSpec extends WordSpec with ScalaFutures {

  import PingPongExample._

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(100, Millis)))

  "A local basic example" must {
    "show register" in {
      val system = ActorSystem[Nothing](guardian, "PingPongExample")
      system.whenTerminated.futureValue
    }
  }
}
