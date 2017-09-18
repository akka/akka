/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.cluster.ClusterEvent._
import akka.typed.TypedSpec
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.adapter._
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
      akka.remote.artery.canonical.port = 25552
      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
      akka.actor {
        serialize-messages = off
        allow-java-serialization = off
      }
    """)
}

class ClusterApiSpec extends TypedSpec(ClusterApiSpec.config) with ScalaFutures {

  val testSettings = TestKitSettings(adaptedSystem)
  val clusterNode1 = Cluster(adaptedSystem)
  val untypedSystem1 = ActorSystemAdapter.toUntyped(adaptedSystem)

  val system2 = akka.actor.ActorSystem(
    adaptedSystem.name,
    ConfigFactory.parseString("akka.remote.artery.canonical.port = 25553").withFallback(ClusterApiSpec.config))
  val adaptedSystem2 = system2.toTyped
  val clusterNode2 = Cluster(adaptedSystem2)

  object `A typed cluster` {

    def `01 must join a cluster and observe events from both sides`() = {
      val node1Probe = TestProbe[MemberEvent]()(adaptedSystem, testSettings)
      clusterNode1.subscriptions ! Subscribe(node1Probe.ref)

      clusterNode1.manager ! Join(clusterNode1.selfMember.address)
      node1Probe.expectMsgType[MemberUp].member.uniqueAddress == clusterNode1.selfMember.uniqueAddress

      val probe = TestProbe[SelfUp]()(adaptedSystem2, testSettings)
      clusterNode2.subscriptions ! OnSelfUp(probe.ref)

      clusterNode2.manager ! Join(clusterNode1.selfMember.address)
      probe.expectMsgType[SelfUp]
      node1Probe.expectMsgType[MemberJoined].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
      node1Probe.expectMsgType[MemberUp].member.uniqueAddress == clusterNode1.selfMember.uniqueAddress

      clusterNode2.manager ! Leave(clusterNode2.selfMember.address)

      node1Probe.expectMsgType[MemberLeft].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
      node1Probe.expectMsgType[MemberExited].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
      node1Probe.expectMsgType[MemberRemoved].member.uniqueAddress == clusterNode2.selfMember.uniqueAddress
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    Await.result(system2.terminate(), 3.seconds)
  }

}
