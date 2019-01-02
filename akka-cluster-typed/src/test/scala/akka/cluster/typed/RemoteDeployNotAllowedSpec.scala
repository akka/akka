/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

object RemoteDeployNotAllowedSpec {
  def config = ConfigFactory.parseString(
    s"""
    akka {
      loglevel = warning
      actor {
        provider = cluster
        warn-about-java-serializer-usage = off
        serialize-creators = off
      }
      remote.netty.tcp.port = 0
      remote.artery {
        canonical {
          hostname = 127.0.0.1
          port = 0
        }
      }
      cluster.jmx.enabled = false
    }
    """)

  def configWithRemoteDeployment(otherSystemPort: Int) = ConfigFactory.parseString(
    s"""
      akka.actor.deployment {
        "/*" {
          remote = "akka://sampleActorSystem@127.0.0.1:$otherSystemPort"
        }
      }
    """).withFallback(config)
}

class RemoteDeployNotAllowedSpec extends ScalaTestWithActorTestKit(RemoteDeployNotAllowedSpec.config) with WordSpecLike {

  "Typed cluster" must {

    "not allow remote deployment" in {
      val node1 = Cluster(system)
      node1.manager ! Join(node1.selfMember.address)
      val probe = TestProbe[AnyRef]()(system)

      trait GuardianProtocol
      case class SpawnChild(name: String) extends GuardianProtocol
      case object SpawnAnonymous extends GuardianProtocol

      val guardianBehavior = Behaviors.receive[GuardianProtocol] { (ctx, msg) ⇒

        msg match {
          case SpawnChild(name) ⇒
            // this should throw
            try {
              ctx.spawn(
                Behaviors.setup[AnyRef] { ctx ⇒ Behaviors.empty },
                name)
            } catch {
              case ex: Exception ⇒ probe.ref ! ex
            }
            Behaviors.same

          case SpawnAnonymous ⇒
            // this should throw
            try {
              ctx.spawnAnonymous(Behaviors.setup[AnyRef] { ctx ⇒ Behaviors.empty })
            } catch {
              case ex: Exception ⇒ probe.ref ! ex
            }
            Behaviors.same
        }

      }

      val system2 = ActorSystem(guardianBehavior, system.name,
        RemoteDeployNotAllowedSpec.configWithRemoteDeployment(node1.selfMember.address.port.get))
      try {
        val node2 = Cluster(system2)
        node2.manager ! Join(node1.selfMember.address)

        system2 ! SpawnChild("remoteDeployed")
        probe.expectMessageType[Exception].getMessage should ===("Remote deployment not allowed for typed actors")

        system2 ! SpawnAnonymous
        probe.expectMessageType[Exception].getMessage should ===("Remote deployment not allowed for typed actors")
      } finally {
        ActorTestKit.shutdown(system2, 5.seconds)
      }
    }
  }

}
