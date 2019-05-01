/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.actor.dungeon.ChildrenContainer
import akka.remote.transport.ThrottlerTransportAdapter.ForceDisassociate
import akka.remote.{ AddressUidExtension, RARP }
import akka.testkit.TestActors.EchoActor
import akka.testkit._
import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

object ActorsLeakSpec {

  val config = ConfigFactory.parseString("""
       akka.actor.provider = remote
       akka.remote.artery.enabled = false
       akka.remote.classic.netty.tcp.applied-adapters = ["trttl"]
       #akka.remote.log-lifecycle-events = on
       akka.remote.classic.transport-failure-detector.heartbeat-interval = 1 s
       akka.remote.classic.transport-failure-detector.acceptable-heartbeat-pause = 3 s
       akka.remote.classic.quarantine-after-silence = 3 s
       akka.test.filter-leeway = 12 s
      """)

  def collectLiveActors(root: Option[ActorRef]): immutable.Seq[ActorRef] = {

    def recurse(node: ActorRef): List[ActorRef] = {
      val children: List[ActorRef] = node match {
        case wc: ActorRefWithCell =>
          val cell = wc.underlying

          cell.childrenRefs match {
            case ChildrenContainer.TerminatingChildrenContainer(_, _, _)                                  => Nil
            case ChildrenContainer.TerminatedChildrenContainer | ChildrenContainer.EmptyChildrenContainer => Nil
            case _: ChildrenContainer.NormalChildrenContainer                                             => cell.childrenRefs.children.toList
            case _                                                                                        => Nil
          }
        case _ => Nil
      }

      node :: children.flatMap(recurse)
    }

    root match {
      case Some(node) => recurse(node)
      case None       => immutable.Seq.empty
    }
  }

  class StoppableActor extends Actor {
    override def receive = {
      case "stop" => context.stop(self)
    }
  }

}

class ActorsLeakSpec extends AkkaSpec(ActorsLeakSpec.config) with ImplicitSender {
  import ActorsLeakSpec._

  "Remoting" must {

    "not leak actors" in {
      system.actorOf(Props[EchoActor], "echo")
      val echoPath = RootActorPath(RARP(system).provider.getDefaultAddress) / "user" / "echo"

      val targets = List("/system/endpointManager", "/system/transports").map { path =>
        system.actorSelection(path) ! Identify(0)
        expectMsgType[ActorIdentity].ref
      }

      val initialActors = targets.flatMap(collectLiveActors).toSet

      //Clean shutdown case
      for (_ <- 1 to 3) {

        val remoteSystem =
          ActorSystem(
            "remote",
            ConfigFactory.parseString("akka.remote.classic.netty.tcp.port = 0").withFallback(config))

        try {
          val probe = TestProbe()(remoteSystem)

          remoteSystem.actorSelection(echoPath).tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity].ref.nonEmpty should be(true)

        } finally {
          remoteSystem.terminate()
        }

        Await.ready(remoteSystem.whenTerminated, 10.seconds)
      }

      // Quarantine an old incarnation case
      for (_ <- 1 to 3) {
        //always use the same address
        val remoteSystem =
          ActorSystem(
            "remote",
            ConfigFactory.parseString("""
                akka.remote.artery.enabled = false
                akka.remote.classic.netty.tcp.port = 2553
              """.stripMargin).withFallback(config))

        try {
          val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

          remoteSystem.actorOf(Props[StoppableActor], "stoppable")

          // the message from remote to local will cause inbound connection established
          val probe = TestProbe()(remoteSystem)
          remoteSystem.actorSelection(echoPath).tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity].ref.nonEmpty should be(true)

          val beforeQuarantineActors = targets.flatMap(collectLiveActors).toSet

          // it must not quarantine the current connection
          @silent
          val addressUid = AddressUidExtension(remoteSystem).addressUid + 1
          RARP(system).provider.transport.quarantine(remoteAddress, Some(addressUid), "test")

          // the message from local to remote should reuse passive inbound connection
          system.actorSelection(RootActorPath(remoteAddress) / "user" / "stoppable") ! Identify(1)
          expectMsgType[ActorIdentity].ref.nonEmpty should be(true)

          val afterQuarantineActors = targets.flatMap(collectLiveActors).toSet

          assertResult(beforeQuarantineActors)(afterQuarantineActors)

        } finally {
          remoteSystem.terminate()
        }

        Await.ready(remoteSystem.whenTerminated, 10.seconds)

      }

      // Missing SHUTDOWN case
      for (_ <- 1 to 3) {

        val remoteSystem =
          ActorSystem(
            "remote",
            ConfigFactory.parseString("""
                 akka.remote.artery.enabled = off
                 akka.remote.classic.netty.tcp.port = 0
              """.stripMargin).withFallback(config))
        val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

        try {
          val probe = TestProbe()(remoteSystem)

          remoteSystem.actorSelection(echoPath).tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity].ref.nonEmpty should be(true)

          // This will make sure that no SHUTDOWN message gets through
          Await.ready(RARP(system).provider.transport.managementCommand(ForceDisassociate(remoteAddress)), 3.seconds)

        } finally {
          remoteSystem.terminate()
        }

        EventFilter.warning(pattern = "Association with remote system", occurrences = 1).intercept {
          Await.ready(remoteSystem.whenTerminated, 10.seconds)
        }
      }

      // Remote idle for too long case
      val remoteSystem =
        ActorSystem("remote", ConfigFactory.parseString("akka.remote.classic.netty.tcp.port = 0").withFallback(config))
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      remoteSystem.actorOf(Props[StoppableActor], "stoppable")

      try {
        val probe = TestProbe()(remoteSystem)

        remoteSystem.actorSelection(echoPath).tell(Identify(1), probe.ref)
        probe.expectMsgType[ActorIdentity].ref.nonEmpty should be(true)

        // Watch a remote actor - this results in system message traffic
        system.actorSelection(RootActorPath(remoteAddress) / "user" / "stoppable") ! Identify(1)
        val remoteActor = expectMsgType[ActorIdentity].ref.get
        watch(remoteActor)
        remoteActor ! "stop"
        expectTerminated(remoteActor)
        // All system messages has been acked now on this side

        // This will make sure that no SHUTDOWN message gets through
        Await.ready(RARP(system).provider.transport.managementCommand(ForceDisassociate(remoteAddress)), 3.seconds)

      } finally {
        remoteSystem.terminate()
      }

      EventFilter.warning(pattern = "Association with remote system", occurrences = 1).intercept {
        Await.ready(remoteSystem.whenTerminated, 10.seconds)
      }

      EventFilter[TimeoutException](occurrences = 1).intercept {}

      awaitAssert(assertResult(initialActors)(targets.flatMap(collectLiveActors).toSet), 10.seconds)
    }

  }

}
