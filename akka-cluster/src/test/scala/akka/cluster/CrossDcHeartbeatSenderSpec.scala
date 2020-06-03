/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.collection.immutable.SortedSet

import akka.actor.{ ActorSelection, Address, Props }
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterHeartbeatSender.Heartbeat
import akka.cluster.CrossDcHeartbeatSenderSpec.TestCrossDcHeartbeatSender
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

object CrossDcHeartbeatSenderSpec {
  class TestCrossDcHeartbeatSender(probe: TestProbe) extends CrossDcHeartbeatSender {
    // disable register for cluster events
    override def preStart(): Unit = {}

    override def heartbeatReceiver(address: Address): ActorSelection = {
      context.actorSelection(probe.ref.path)
    }
  }
}

class CrossDcHeartbeatSenderSpec extends AkkaSpec("""
    akka.loglevel = DEBUG
    akka.actor.provider = cluster 
    akka.cluster.failure-detector.heartbeat-interval = 0.2s
    akka.cluster.multi-data-center {
      self-data-center = "dc1"
      heartbeat-interval = 0.2s
    }
  """) with ImplicitSender {
  "CrossDcHeartBeatSender" should {
    "increment heart beat sequence nr" in {
      val probe = TestProbe()
      Cluster(system).join(Cluster(system).selfMember.address)
      awaitAssert(Cluster(system).selfMember.status == MemberStatus.Up)
      val underTest = system.actorOf(Props(new TestCrossDcHeartbeatSender(probe)))
      underTest ! CurrentClusterState(
        members = SortedSet(
          Cluster(system).selfMember,
          Member(UniqueAddress(Address("akka", system.name), 2L), Set("dc-dc2")).copy(status = MemberStatus.Up)))

      probe.expectMsgType[Heartbeat].sequenceNr shouldEqual 1
      probe.expectMsgType[Heartbeat].sequenceNr shouldEqual 2
    }
  }
}
