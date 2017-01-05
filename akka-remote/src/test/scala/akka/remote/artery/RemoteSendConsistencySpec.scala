/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorIdentity, ActorRef, ActorSystem, Deploy, Identify, PoisonPill, Props, RootActorPath }
import akka.remote.RARP
import akka.testkit.{ AkkaSpec, ImplicitSender, TestActors, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.duration._

class RemoteSendConsistencySpec extends AbstractRemoteSendConsistencySpec(ArterySpecSupport.defaultConfig)

class RemoteSendConsistencyWithThreeLanesSpec extends AbstractRemoteSendConsistencySpec(
  ConfigFactory.parseString("""
      akka.remote.artery.advanced.outbound-lanes = 3
      akka.remote.artery.advanced.inbound-lanes = 3
    """).withFallback(ArterySpecSupport.defaultConfig))

abstract class AbstractRemoteSendConsistencySpec(config: Config) extends ArteryMultiNodeSpec(config) with ImplicitSender {

  val systemB = newRemoteSystem(name = Some("systemB"))
  val addressB = address(systemB)
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

}
