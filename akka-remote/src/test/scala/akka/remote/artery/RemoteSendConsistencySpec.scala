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

object RemoteSendConsistencySpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = 0
     }
  """)

}

class RemoteSendConsistencySpec extends AkkaSpec(RemoteSendConsistencySpec.config) with ImplicitSender {

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
        expectMsgType[ActorIdentity].ref.get
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
      val actorOnSystemB = systemB.actorOf(Props(new Actor {
        def receive = {
          case i: Int ⇒ sender() ! i
        }
      }), "echo2")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo2") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      val senderProps = Props(new Actor {
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

      system.actorOf(senderProps)
      system.actorOf(senderProps)
      system.actorOf(senderProps)
      system.actorOf(senderProps)

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
