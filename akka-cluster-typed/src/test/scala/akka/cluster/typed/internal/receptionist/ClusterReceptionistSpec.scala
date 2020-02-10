/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.RootActorPath
import akka.actor.testkit.typed.FishingOutcome
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.FishingOutcomes
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.typed.Cluster
import akka.cluster.typed.Down
import akka.cluster.typed.Join
import akka.cluster.typed.JoinSeedNodes
import akka.cluster.typed.Leave
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object ClusterReceptionistSpec {
  val config = ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG # issue #24960
      akka.actor.provider = cluster
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.classic.netty.tcp.host = 127.0.0.1
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.remote.retry-gate-closed-for = 1 s

      akka.cluster.typed.receptionist {
        pruning-interval = 1 s
      }

      akka.cluster {
        jmx.multi-mbeans-in-same-jvm = on
        failure-detector.acceptable-heartbeat-pause = 3s
      }
    """)

  case object Pong extends CborSerializable
  trait PingProtocol
  case class Ping(respondTo: ActorRef[Pong.type]) extends PingProtocol with CborSerializable
  case object Perish extends PingProtocol with CborSerializable

  val pingPongBehavior = Behaviors.receive[PingProtocol] { (_, msg) =>
    msg match {
      case Ping(respondTo) =>
        respondTo ! Pong
        Behaviors.same

      case Perish =>
        Behaviors.stopped
    }
  }

  val PingKey = ServiceKey[PingProtocol]("pingy")
  val AnotherKey = ServiceKey[PingProtocol]("pingy-2")
}

class ClusterReceptionistSpec extends AnyWordSpec with Matchers with LogCapturing {

  import ClusterReceptionistSpec._
  import Receptionist._

  "The cluster receptionist" must {

    "eventually replicate registrations to the other side" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-1", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {
        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)
        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service = testKit1.spawn(pingPongBehavior)
        testKit1.system.receptionist ! Register(PingKey, service, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service))

        val PingKey.Listing(remoteServiceRefs) = regProbe2.expectMessageType[Listing]
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe2.ref)
        regProbe2.expectMessage(Pong)

        service ! Perish
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "remove registrations when node dies" in {
      testNodeRemoval(down = true)
    }

    "remove registrations when node leaves" in {
      testNodeRemoval(down = false)
    }

    def testNodeRemoval(down: Boolean): Unit = {
      val testKit1 = ActorTestKit(s"ClusterReceptionistSpec-test-3-$down", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service1 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))

        regProbe1.expectMessage(Listing(PingKey, Set(service1)))

        val service2 = testKit2.spawn(pingPongBehavior)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        val serviceRefs2 = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        serviceRefs2.size should ===(2)

        if (down) {
          // abrupt termination
          system2.terminate()
          Await.ready(system2.whenTerminated, 10.seconds)
          clusterNode1.manager ! Down(clusterNode2.selfMember.address)
        } else {
          clusterNode1.manager ! Leave(clusterNode2.selfMember.address)
        }

        regProbe1.awaitAssert({
          // we will also potentially get an update that the service was unreachable before the expected one
          regProbe1.expectMessage(10.seconds, Listing(PingKey, Set(service1)))
        }, 10.seconds)

        // register another after removal
        val service1b = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1b, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1b))
        regProbe1.expectMessage(Listing(PingKey, Set(service1, service1b)))

      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "not remove registrations when self is shutdown" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-4", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service1 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))

        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)

        val service2 = testKit2.spawn(pingPongBehavior)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(2)

        akka.cluster.Cluster(system1).shutdown()

        regProbe2.expectNoMessage(3.seconds)

        clusterNode2.manager ! Down(clusterNode1.selfMember.address)
        // service1 removed
        regProbe2.awaitAssert({
          // we will also potentially get an update that the service was unreachable before the expected one
          regProbe2.expectMessage(10.seconds, Listing(PingKey, Set(service2)))
        }, 10.seconds)
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }

    }

    "work with services registered before node joins cluster" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-5", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service2 = testKit2.spawn(pingPongBehavior)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        val reply2 = TestProbe[Listing]()(system2)
        // awaitAssert because it is not immediately included in the registry (round trip to ddata)
        reply2.awaitAssert {
          system2.receptionist ! Find(PingKey, reply2.ref)
          reply2.receiveMessage().serviceInstances(PingKey) should ===(Set(service2))
        }

        // and it shouldn't be removed (wait longer than pruning-interval)
        Thread.sleep(2000)
        system2.receptionist ! Find(PingKey, reply2.ref)
        reply2.receiveMessage().serviceInstances(PingKey) should ===(Set(service2))

        // then we join the cluster
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)
        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        // and the subscriber on node1 should see the service
        val remoteServiceRefs = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe1.ref)
        regProbe1.expectMessage(Pong)

        // abrupt termination
        system2.terminate()
        Await.ready(system2.whenTerminated, 10.seconds)
        clusterNode1.manager ! Down(clusterNode2.selfMember.address)
        regProbe1.awaitAssert({

          // we will also potentially get an update that the service was unreachable before the expected one
          regProbe1.expectMessage(10.seconds, Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
        }, 10.seconds)
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "handle a new incarnation of the same node well" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-6", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service2 = testKit2.spawn(pingPongBehavior, "instance")
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        // make sure we saw the first incarnation on node1
        val remoteServiceRefs = regProbe1.expectMessageType[Listing].serviceInstances(PingKey)
        val theRef = remoteServiceRefs.head
        theRef ! Ping(regProbe1.ref)
        regProbe1.expectMessage(Pong)

        // abrupt termination but then a node with the same host:port comes online quickly
        system1.log.debug("Terminating system2: [{}]", clusterNode2.selfMember.uniqueAddress)
        system2.terminate()
        Await.ready(system2.whenTerminated, 10.seconds)

        val testKit3 = ActorTestKit(
          system1.name,
          ConfigFactory.parseString(s"""
            akka.remote.classic.netty.tcp.port = ${clusterNode2.selfMember.address.port.get}
            akka.remote.artery.canonical.port = ${clusterNode2.selfMember.address.port.get}
            # retry joining when existing member removed
            akka.cluster.retry-unsuccessful-join-after = 1s
          """).withFallback(config))

        try {
          val system3 = testKit3.system
          val clusterNode3 = Cluster(system3)
          system1.log
            .debug("Starting system3 at same hostname port as system2: [{}]", clusterNode3.selfMember.uniqueAddress)
          // using JoinSeedNodes instead of Join to retry the join when existing member removed
          clusterNode3.manager ! JoinSeedNodes(List(clusterNode1.selfMember.address))
          val regProbe3 = TestProbe[Any]()(system3)

          // and registers the same service key
          val service3 = testKit3.spawn(pingPongBehavior, "instance")
          val service3Uid = service3.path.uid
          system3.log.debug("Spawning/registering ping service in new incarnation {}", service3)
          system3.receptionist ! Register(PingKey, service3, regProbe3.ref)
          regProbe3.expectMessage(Registered(PingKey, service3))
          system3.log.debug("Registered actor [{}] for system3", service3)

          // make sure it joined fine and node1 has upped it
          regProbe1.awaitAssert(
            {
              clusterNode1.state.members.exists(
                m =>
                  m.uniqueAddress == clusterNode3.selfMember.uniqueAddress &&
                  m.status == MemberStatus.Up &&
                  !clusterNode1.state.unreachable(m)) should ===(true)
            },
            10.seconds)

          // we should get either empty message and then updated with the new incarnation actor
          // or just updated with the new service directly
          val msg = regProbe1.fishForMessage(20.seconds) {
            case PingKey.Listing(entries) if entries.size == 1 => FishingOutcome.Complete
            case _: Listing                                    => FishingOutcome.ContinueAndIgnore
          }
          val PingKey.Listing(entries) = msg.last
          entries should have size 1
          val ref = entries.head
          val service3RemotePath = RootActorPath(clusterNode3.selfMember.address) / "user" / "instance"
          ref.path should ===(service3RemotePath)
          ref.path.uid should ===(service3Uid)

          ref ! Ping(regProbe1.ref)
          regProbe1.expectMessage(Pong)

        } finally {
          testKit3.shutdownTestKit()
        }
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    // reproducer of issue #26284
    "handle a new incarnation of the same node that is no longer part of same cluster" in {
      val testKit1 = ActorTestKit(
        "ClusterReceptionistSpec-test-7",
        ConfigFactory.parseString("""
          akka.cluster {
            failure-detector.acceptable-heartbeat-pause = 20s
          }
          akka.cluster.typed.receptionist {
            # it can be stressed more by using all
            write-consistency = all
          }
          """).withFallback(ClusterReceptionistSpec.config))
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)
        val reply1 = TestProbe[Listing]()(system1)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        val service1 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))
        regProbe1.expectMessage(Listing(PingKey, Set(service1)))

        val service2 = testKit2.spawn(pingPongBehavior, "instance")
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe2.expectMessage(Registered(PingKey, service2))

        // make sure we saw the first incarnation on node1
        regProbe1.expectMessageType[Listing].serviceInstances(PingKey).size should ===(2)

        // abrupt termination but then a node with the same host:port comes online quickly
        system1.log.debug("Terminating system2: [{}]", clusterNode2.selfMember.uniqueAddress)
        system2.terminate()
        Await.ready(system2.whenTerminated, 10.seconds)

        val testKit3 = ActorTestKit(
          system1.name,
          ConfigFactory.parseString(s"""
            akka.remote.classic.netty.tcp.port = ${clusterNode2.selfMember.address.port.get}
            akka.remote.artery.canonical.port = ${clusterNode2.selfMember.address.port.get}
          """).withFallback(config))

        try {
          val system3 = testKit3.system
          val regProbe3 = TestProbe[Any]()(system3)
          val clusterNode3 = Cluster(system3)
          system1.log
            .debug("Starting system3 at same hostname port as system2 [{}]", clusterNode3.selfMember.uniqueAddress)
          // joining itself, i.e. not same cluster
          clusterNode3.manager ! Join(clusterNode3.selfMember.address)
          regProbe3.awaitAssert(clusterNode3.state.members.count(_.status == MemberStatus.Up) should ===(1))

          // register another
          Thread.sleep(2000)
          val service1b = testKit1.spawn(pingPongBehavior)
          system1.receptionist ! Register(PingKey, service1b, regProbe1.ref)

          val service1c = testKit1.spawn(pingPongBehavior)
          system1.receptionist ! Register(PingKey, service1c, regProbe1.ref)

          system3.receptionist ! Subscribe(PingKey, regProbe3.ref)
          // shouldn't get anything from the other cluster
          regProbe3.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

          // and registers the same service key
          val service3 = testKit3.spawn(pingPongBehavior, "instance")
          system3.log.debug("Spawning/registering ping service in new incarnation {}", service3)
          system3.receptionist ! Register(PingKey, service3, regProbe3.ref)
          regProbe3.expectMessage(Registered(PingKey, service3))
          system3.log.debug("Registered actor [{}] for system3", service3)

          // shouldn't get anything from the other cluster
          regProbe3.expectMessage(Listing(PingKey, Set(service3)))

          reply1.expectNoMessage(1.second)
          system1.receptionist ! Find(PingKey, reply1.ref)
          (reply1.receiveMessage().serviceInstances(PingKey) should contain).allOf(service1, service1b, service1c)

          reply1.expectNoMessage(1.second)
          system1.receptionist ! Find(PingKey, reply1.ref)
          (reply1.receiveMessage().serviceInstances(PingKey) should contain).allOf(service1, service1b, service1c)

        } finally {
          testKit3.shutdownTestKit()
        }
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "not lose removals on concurrent updates to same key" in {
      val config = ConfigFactory.parseString("""
          # disable delta propagation so we can have repeatable concurrent writes
          # without delta reaching between nodes already
          akka.cluster.distributed-data.delta-crdt.enabled=false
        """).withFallback(ClusterReceptionistSpec.config)
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-8", config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system

      val TheKey = ServiceKey[AnyRef]("whatever")
      try {
        val clusterNode1 = Cluster(system1)
        val clusterNode2 = Cluster(system2)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[AnyRef]()(system1)
        val regProbe2 = TestProbe[AnyRef]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2))

        // one actor on each node up front
        val actor1 = testKit1.spawn(Behaviors.receive[AnyRef] {
          case (ctx, "stop") =>
            ctx.log.info("Stopping")
            Behaviors.stopped
          case _ => Behaviors.same
        }, "actor1")
        val actor2 = testKit2.spawn(Behaviors.empty[AnyRef], "actor2")

        system1.receptionist ! Register(TheKey, actor1)
        system1.receptionist ! Subscribe(TheKey, regProbe1.ref)
        regProbe1.awaitAssert(regProbe1.expectMessage(Listing(TheKey, Set(actor1))), 5.seconds)

        system2.receptionist ! Subscribe(TheKey, regProbe2.ref)
        regProbe2.fishForMessage(10.seconds) {
          case TheKey.Listing(actors) if actors.nonEmpty =>
            FishingOutcomes.complete
          case _ => FishingOutcomes.continue
        }
        system1.log.info("Saw actor on both nodes")

        // TheKey -> Set(actor1) seen by both nodes, now,
        // remove on node1 and add on node2 (hopefully) concurrently
        system2.receptionist ! Register(TheKey, actor2, regProbe2.ref)
        actor1 ! "stop"
        regProbe2.expectMessage(Registered(TheKey, actor2))
        system2.log.info("actor2 registered")

        // we should now, eventually, see the removal on both nodes
        regProbe1.fishForMessage(10.seconds) {
          case TheKey.Listing(actors) if actors.size == 1 =>
            FishingOutcomes.complete
          case _ =>
            FishingOutcomes.continue
        }
        regProbe2.fishForMessage(10.seconds) {
          case TheKey.Listing(actors) if actors.size == 1 =>
            FishingOutcomes.complete
          case _ =>
            FishingOutcomes.continue
        }

      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }
    }

    "not conflict with the ClusterClient receptionist default name" in {
      val testKit = ActorTestKit(s"ClusterReceptionistSpec-test-9", ClusterReceptionistSpec.config)
      try {
        testKit.system.systemActorOf(Behaviors.ignore, "receptionist")
      } finally {
        testKit.shutdownTestKit()
      }
    }

    "handle unregistration and re-registration of services" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-10", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        // register and verify seen on remote side
        val service1 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)

        // another service for the same key
        val service2 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service2, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service2))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(2)

        // unregister service1 and verify
        system1.receptionist ! Deregister(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Deregistered(PingKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)

        // re-register and verify
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(2)

        // cover a race between termination and unregistration as well (should lead to only one update)
        system1.receptionist ! Deregister(PingKey, service1, regProbe1.ref)
        service1 ! Perish

        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)
        regProbe2.expectNoMessage(1.second)

        akka.cluster.Cluster(system1).shutdown()
        clusterNode2.manager ! Down(clusterNode1.selfMember.address)
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }

    }

    "handle unregistration per key not per actor" in {
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-11", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
        system2.receptionist ! Subscribe(AnotherKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(AnotherKey, Set.empty[ActorRef[PingProtocol]]))

        // register same actor for two service keys and verify seen on remote side
        val service1 = testKit1.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)
        system1.receptionist ! Register(AnotherKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(AnotherKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(AnotherKey).size should ===(1)

        // unregister service1 for one of the keys and verify
        system1.receptionist ! Deregister(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Deregistered(PingKey, service1))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(0)
        system2.receptionist ! Find(AnotherKey, regProbe2.ref)
        regProbe2.expectMessageType[Listing].serviceInstances(AnotherKey).size should ===(1)

        system1.receptionist ! Find(PingKey, regProbe1.ref)
        regProbe1.expectMessageType[Listing].serviceInstances(PingKey).size should ===(0)
        system1.receptionist ! Find(AnotherKey, regProbe1.ref)
        regProbe1.expectMessageType[Listing].serviceInstances(AnotherKey).size should ===(1)

        akka.cluster.Cluster(system1).shutdown()
        clusterNode2.manager ! Down(clusterNode1.selfMember.address)
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }

    }

    "handle concurrent unregistration and registration on different nodes" in {
      // this covers the fact that with ddata a removal can be lost
      val testKit1 = ActorTestKit("ClusterReceptionistSpec-test-12", ClusterReceptionistSpec.config)
      val system1 = testKit1.system
      val testKit2 = ActorTestKit(system1.name, system1.settings.config)
      val system2 = testKit2.system
      try {

        val clusterNode1 = Cluster(system1)
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        val clusterNode2 = Cluster(system2)
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)

        val regProbe1 = TestProbe[Any]()(system1)
        val regProbe2 = TestProbe[Any]()(system2)

        regProbe1.awaitAssert(clusterNode1.state.members.count(_.status == MemberStatus.Up) should ===(2), 10.seconds)

        system2.receptionist ! Subscribe(PingKey, regProbe2.ref)
        regProbe2.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))
        system1.receptionist ! Subscribe(PingKey, regProbe1.ref)
        regProbe1.expectMessage(Listing(PingKey, Set.empty[ActorRef[PingProtocol]]))

        // register an actor on one side and verify seen on both
        val service1 = testKit1.spawn(pingPongBehavior)
        val service2 = testKit2.spawn(pingPongBehavior)
        system1.receptionist ! Register(PingKey, service1, regProbe1.ref)
        regProbe1.expectMessage(Registered(PingKey, service1))
        regProbe1.expectMessage(Listing(PingKey, Set(service1)))
        regProbe2.expectMessageType[Listing].serviceInstances(PingKey).size should ===(1)

        // then concurrently register on one node and unregister on the other node for the same key (ofc racy)
        system1.receptionist ! Deregister(PingKey, service1, regProbe1.ref)
        system2.receptionist ! Register(PingKey, service2, regProbe2.ref)
        regProbe1.expectMessage(Deregistered(PingKey, service1))
        regProbe2.expectMessage(Registered(PingKey, service2))

        regProbe2.fishForMessage(3.seconds) {
          case PingKey.Listing(actors) if actors == Set(service2) => FishingOutcomes.complete
          case PingKey.Listing(actors) if actors.size == 2        =>
            // we may see both actors before we see the removal
            FishingOutcomes.continueAndIgnore
        }

        regProbe1.fishForMessage(3.seconds) {
          case PingKey.Listing(actors) if actors.size == 1 => FishingOutcomes.complete
          case PingKey.Listing(actors) if actors.isEmpty   => FishingOutcomes.continueAndIgnore
        }

        akka.cluster.Cluster(system1).shutdown()
        clusterNode2.manager ! Down(clusterNode1.selfMember.address)
      } finally {
        testKit1.shutdownTestKit()
        testKit2.shutdownTestKit()
      }

    }
    // Fixme concurrent registration and unregistration
  }
}
