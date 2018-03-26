/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.{ Actor, ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, Props, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestKit }
import com.typesafe.config.ConfigFactory
import MessageLoggingSpec._

object MessageLoggingSpec {
  val config = ConfigFactory.parseString(
    """
     akka.loglevel = INFO // debug makes this test fail intentionally
     akka.actor.provider = remote
     akka.remote {
        log-received-messages = on
        log-sent-messages = on

        enabled-transports = ["akka.remote.netty.tcp"]
        netty.tcp {
          hostname = localhost
          port = 0
        }
     }
    """.stripMargin)

  case class BadMsg(msg: String) {
    override def toString = throw new RuntimeException("Don't log me")

  }

  class BadActor extends Actor {
    override def receive = {
      case msg ⇒
        sender() ! BadMsg("hah")
    }
  }
}

class MessageLoggingSpec extends AkkaSpec(MessageLoggingSpec.config) with ImplicitSender {

  val remoteSystem = ActorSystem("remote-sys", config)
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

