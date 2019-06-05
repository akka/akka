/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.{ Actor, ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, Props, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestKit }
import com.typesafe.config.{ Config, ConfigFactory }
import MessageLoggingSpec._

object MessageLoggingSpec {
  def config(artery: Boolean) = ConfigFactory.parseString(s"""
     akka.loglevel = info // debug makes this test fail intentionally
     akka.actor.provider = remote
     akka.remote {
     
      classic {
        log-received-messages = on
        log-sent-messages = on
        netty.tcp {
          hostname = localhost
          port = 0
        }

      } 
     
      artery {
        enabled = $artery
        transport = aeron-udp
        canonical.hostname = localhost
        canonical.port = 0
        log-received-messages = on
        log-sent-messages = on
      }
     }
    """.stripMargin)

  case class BadMsg(msg: String) {
    override def toString = throw new RuntimeException("Don't log me")

  }

  class BadActor extends Actor {
    override def receive = {
      case _ =>
        sender() ! BadMsg("hah")
    }
  }
}

class ArteryMessageLoggingSpec extends MessageLoggingSpec(config(true))
class ClassicMessageLoggingSpec extends MessageLoggingSpec(config(false))

abstract class MessageLoggingSpec(config: Config) extends AkkaSpec(config) with ImplicitSender {

  val remoteSystem = ActorSystem("remote-sys", ConfigFactory.load(config))
  val remoteAddress = remoteSystem.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  "Message logging" must {
    "not be on if debug logging not enabled" in {
      remoteSystem.actorOf(Props[BadActor], "bad")
      val as = system.actorSelection(RootActorPath(remoteAddress) / "user" / "bad")
      as ! Identify("bad")
      val ref = expectMsgType[ActorIdentity].ref.get
      ref ! "hello"
      expectMsgType[BadMsg]
    }
  }

  override protected def afterTermination(): Unit = {
    TestKit.shutdownActorSystem(remoteSystem)
  }
}
