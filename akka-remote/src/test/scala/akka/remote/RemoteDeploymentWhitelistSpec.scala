/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor._
import akka.remote.transport._
import akka.testkit._
import com.typesafe.config._

import scala.concurrent.duration._

object RemoteDeploymentWhitelistSpec {

  class EchoWhitelisted extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case ex: Exception => throw ex
      case x             => target = sender(); sender() ! x
    }

    override def preStart(): Unit = {}
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable): Unit = {}
    override def postStop(): Unit = {
      target ! "postStop"
    }
  }

  class EchoNotWhitelisted extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case ex: Exception => throw ex
      case x             => target = sender(); sender() ! x
    }

    override def preStart(): Unit = {}
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable): Unit = {}
    override def postStop(): Unit = {
      target ! "postStop"
    }
  }

  val cfg: Config = ConfigFactory.parseString(s"""
    akka {
      actor.provider = remote

      remote {
        enabled-transports = [
          "akka.remote.test",
          "akka.remote.netty.tcp"
        ]

        netty.tcp = {
          port = 0
          hostname = "localhost"
        }

        test {
          transport-class = "akka.remote.transport.TestTransport"
          applied-adapters = []
          registry-key = aX33k0jWKg
          local-address = "test://RemoteDeploymentWhitelistSpec@localhost:12345"
          maximum-payload-bytes = 32000 bytes
          scheme-identifier = test
        }
      }

      actor.deployment {
        /blub.remote = "akka.test://remote-sys@localhost:12346"
        /danger-mouse.remote = "akka.test://remote-sys@localhost:12346"
      }
    }
  """)

  def muteSystem(system: ActorSystem): Unit = {
    system.eventStream.publish(
      TestEvent.Mute(
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*")))
  }
}

class RemoteDeploymentWhitelistSpec
    extends AkkaSpec(RemoteDeploymentWhitelistSpec.cfg)
    with ImplicitSender
    with DefaultTimeout {

  import RemoteDeploymentWhitelistSpec._

  val conf =
    ConfigFactory.parseString("""
      akka.remote.test {
        local-address = "test://remote-sys@localhost:12346"
        maximum-payload-bytes = 48000 bytes
      }

      //#whitelist-config
      akka.remote.deployment {
        enable-whitelist = on
        
        whitelist = [
          "NOT_ON_CLASSPATH", # verify we don't throw if a class not on classpath is listed here
          "akka.remote.RemoteDeploymentWhitelistSpec.EchoWhitelisted"
        ]
      }
      //#whitelist-config
    """).withFallback(system.settings.config).resolve()
  val remoteSystem = ActorSystem("remote-sys", conf)

  override def atStartup() = {
    muteSystem(system)
    remoteSystem.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate|HandleListener)")))
  }

  override def afterTermination(): Unit = {
    shutdown(remoteSystem)
    AssociationRegistry.clear()
  }

  "RemoteDeployment Whitelist" must {

    "allow deploying Echo actor (included in whitelist)" in {
      val r = system.actorOf(Props[EchoWhitelisted], "blub")
      r.path.toString should ===(
        s"akka.test://remote-sys@localhost:12346/remote/akka.test/${getClass.getSimpleName}@localhost:12345/user/blub")
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "not deploy actor not listed in whitelist" in {
      val r = system.actorOf(Props[EchoNotWhitelisted], "danger-mouse")
      r.path.toString should ===(
        s"akka.test://remote-sys@localhost:12346/remote/akka.test/${getClass.getSimpleName}@localhost:12345/user/danger-mouse")
      r ! 42
      expectNoMsg(1.second)
      system.stop(r)
    }
  }
}
