/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorIdentity, ActorSystem, Deploy, ExtendedActorSystem, Identify, Props, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory
import akka.actor.Actor.Receive
import akka.remote.RARP
import akka.testkit.TestActors
import akka.actor.PoisonPill
import akka.testkit.TestProbe
import akka.actor.ActorRef
import com.typesafe.config.Config

object RemoteSendConsistencySpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
     }
  """)

}

class RemoteSendConsistencySpec extends AbstractRemoteSendConsistencySpec(RemoteSendConsistencySpec.config)

class RemoteSendConsistencyWithThreeLanesSpec extends AbstractRemoteSendConsistencySpec(
  ConfigFactory.parseString("""
      akka.remote.artery.advanced.outbound-lanes = 3
      akka.remote.artery.advanced.inbound-lanes = 3
    """).withFallback(RemoteSendConsistencySpec.config))

abstract class AbstractRemoteSendConsistencySpec(config: Config) extends AkkaSpec(config) with ImplicitSender {

  val systemB = ActorSystem("systemB", system.settings.config)
  val addressB = RARP(systemB).provider.getDefaultAddress
  println(addressB)
  val rootB = RootActorPath(addressB)

  "Artery" must {

    "be able to identify a remote actor and ping it" in {
      val actorOnSystemB = systemB.actorOf(Props(new Actor {
        def receive = {
          case "ping" ⇒ sender() ! "pong"
        }
      }), "echo")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity](5.seconds).ref.get
      }

      remoteRef ! "ping"
      expectMsg("pong")

      remoteRef ! "ping"
      expectMsg("pong")

      remoteRef ! "ping"
      expectMsg("pong")
    }

    "not send to remote re-created actor with same name" in {
      val echo = systemB.actorOf(TestActors.echoActorProps, "otherEcho1")
      echo ! 71
      expectMsg(71)
      echo ! PoisonPill
      echo ! 72
      val probe = TestProbe()(systemB)
      probe.watch(echo)
      probe.expectTerminated(echo)
      expectNoMsg(1.second)

      val echo2 = systemB.actorOf(TestActors.echoActorProps, "otherEcho1")
      echo2 ! 73
      expectMsg(73)
      // msg to old ActorRef (different uid) should not get through
      echo2.path.uid should not be (echo.path.uid)
      echo ! 74
      expectNoMsg(1.second)
    }

    "be able to send messages concurrently preserving order" in {
      systemB.actorOf(TestActors.echoActorProps, "echoA")
      systemB.actorOf(TestActors.echoActorProps, "echoB")
      systemB.actorOf(TestActors.echoActorProps, "echoC")

      val remoteRefA = {
        system.actorSelection(rootB / "user" / "echoA") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }
      val remoteRefB = {
        system.actorSelection(rootB / "user" / "echoB") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }
      val remoteRefC = {
        system.actorSelection(rootB / "user" / "echoC") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      def senderProps(remoteRef: ActorRef) = Props(new Actor {
        var counter = 1000
        remoteRef ! counter

        override def receive: Receive = {
          case i: Int ⇒
            if (i != counter) testActor ! s"Failed, expected $counter got $i"
            else if (counter == 0) {
              testActor ! "success"
              context.stop(self)
            } else {
              counter -= 1
              remoteRef ! counter
            }
        }
      }).withDeploy(Deploy.local)

      system.actorOf(senderProps(remoteRefA))
      system.actorOf(senderProps(remoteRefB))
      system.actorOf(senderProps(remoteRefC))
      system.actorOf(senderProps(remoteRefA))

      within(10.seconds) {
        expectMsg("success")
        expectMsg("success")
        expectMsg("success")
        expectMsg("success")
      }
    }

  }

  override def afterTermination(): Unit = shutdown(systemB)

}
