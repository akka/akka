/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.typed

import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.actor.typed.TypedSpec
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._

object ClusterApiSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster
      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1
      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
      akka.coordinated-shutdown.terminate-actor-system = off
      akka.actor {
        serialize-messages = off
        allow-java-serialization = off
      }
    """)
}

class ClusterApiSpec extends TypedSpec(ClusterApiSpec.config) with ScalaFutures {

  val testSettings = TestKitSettings(system)
  val clusterNode1 = Cluster(system)
  val untypedSystem1 = system.toUntyped

  object `A typed cluster` {

    def `01 must join a cluster and observe events from both sides`() = {

      val system2 = akka.actor.ActorSystem(system.name, system.settings.config)
      val adaptedSystem2 = system2.toTyped

      try {
        val clusterNode2 = Cluster(adaptedSystem2)

        val node1Probe = TestProbe[AnyRef]()(system, testSettings)
        val node2Probe = TestProbe[AnyRef]()(adaptedSystem2, testSettings)

        // initial cached selfMember
        clusterNode1.selfMember.status should ===(MemberStatus.Removed)
        clusterNode2.selfMember.status should ===(MemberStatus.Removed)

        // check that subscriptions work
        clusterNode1.subscriptions ! Subscribe(node1Probe.ref, classOf[MemberEvent])
        clusterNode1.manager ! Join(clusterNode1.selfMember.address)
        node1Probe.expectMsgType[MemberUp].member.uniqueAddress == clusterNode1.selfMember.uniqueAddress

        // check that cached selfMember is updated
        node1Probe.awaitAssert(
          clusterNode1.selfMember.status should ===(MemberStatus.Up))

        // subscribing to OnSelfUp when already up
        clusterNode1.subscriptions ! Subscribe(node1Probe.ref, classOf[SelfUp])
        node1Probe.expectMsgType[SelfUp]

        // selfMember update and on up subscription on node 2 when joining
        clusterNode2.subscriptions ! Subscribe(node2Probe.ref, classOf[SelfUp])
        clusterNode2.manager ! Join(clusterNode1.selfMember.address)
        node2Probe.awaitAssert(
          clusterNode2.selfMember.status should ===(MemberStatus.Up))
        node2Probe.expectMsgType[SelfUp]

        // events about node2 joining to subscriber on node1
        node1Probe.expectMsgType[MemberJoined].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
        node1Probe.expectMsgType[MemberUp].member.uniqueAddress == clusterNode1.selfMember.uniqueAddress

        // OnSelfRemoved and subscription events around node2 leaving
        clusterNode2.subscriptions ! Subscribe(node2Probe.ref, classOf[SelfRemoved])
        clusterNode2.manager ! Leave(clusterNode2.selfMember.address)

        // node1 seeing all those transition events
        node1Probe.expectMsgType[MemberLeft].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
        node1Probe.expectMsgType[MemberExited].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
        node1Probe.expectMsgType[MemberRemoved].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress

        // selfMember updated and self removed event gotten
        node2Probe.awaitAssert(
          clusterNode2.selfMember.status should ===(MemberStatus.Removed))
        node2Probe.expectMsg(SelfRemoved(MemberStatus.Exiting))

        // subscribing to SelfRemoved when already removed yields immediate message back
        clusterNode2.subscriptions ! Subscribe(node2Probe.ref, classOf[SelfRemoved])
        node2Probe.expectMsg(SelfRemoved(MemberStatus.Exiting))

        // subscribing to SelfUp when already removed yields nothing
        clusterNode2.subscriptions ! Subscribe(node2Probe.ref, classOf[SelfUp])
        node2Probe.expectNoMsg(100.millis)

      } finally {
        Await.result(system2.terminate(), 3.seconds)
      }
    }
  }

}
