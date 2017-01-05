/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.protobuf

// TODO remove metrics

import akka.cluster._
import akka.actor.{ ExtendedActorSystem, Address }
import collection.immutable.SortedSet
import akka.testkit.AkkaSpec

class ClusterMessageSerializerSpec extends AkkaSpec(
  "akka.actor.provider = cluster") {

  val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def checkSerialization(obj: AnyRef): Unit = {
    val blob = serializer.toBinary(obj)
    val ref = serializer.fromBinary(blob, obj.getClass)
    obj match {
      case env: GossipEnvelope ⇒
        val env2 = obj.asInstanceOf[GossipEnvelope]
        env2.from should ===(env.from)
        env2.to should ===(env.to)
        env2.gossip should ===(env.gossip)
      case _ ⇒
        ref should ===(obj)
    }

  }

  import MemberStatus._

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Joining, Set.empty)
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, Set("r1"))
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving, Set("r2"))
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Exiting, Set("r1", "r2"))
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Down, Set("r3"))
  val f1 = TestMember(Address("akka.tcp", "sys", "f", 2552), Removed, Set("r2", "r3"))

  "ClusterMessages" must {

    "be serializable" in {
      val address = Address("akka.tcp", "system", "some.host.org", 4711)
      val uniqueAddress = UniqueAddress(address, 17)
      val address2 = Address("akka.tcp", "system", "other.host.org", 4711)
      val uniqueAddress2 = UniqueAddress(address2, 18)
      checkSerialization(InternalClusterAction.Join(uniqueAddress, Set("foo", "bar")))
      checkSerialization(ClusterUserAction.Leave(address))
      checkSerialization(ClusterUserAction.Down(address))
      checkSerialization(InternalClusterAction.InitJoin)
      checkSerialization(InternalClusterAction.InitJoinAck(address))
      checkSerialization(InternalClusterAction.InitJoinNack(address))
      checkSerialization(ClusterHeartbeatSender.Heartbeat(address))
      checkSerialization(ClusterHeartbeatSender.HeartbeatRsp(uniqueAddress))

      val node1 = VectorClock.Node("node1")
      val node2 = VectorClock.Node("node2")
      val node3 = VectorClock.Node("node3")
      val node4 = VectorClock.Node("node4")
      val g1 = (Gossip(SortedSet(a1, b1, c1, d1)) :+ node1 :+ node2).seen(a1.uniqueAddress).seen(b1.uniqueAddress)
      val g2 = (g1 :+ node3 :+ node4).seen(a1.uniqueAddress).seen(c1.uniqueAddress)
      val reachability3 = Reachability.empty.unreachable(a1.uniqueAddress, e1.uniqueAddress).unreachable(b1.uniqueAddress, e1.uniqueAddress)
      val g3 = g2.copy(members = SortedSet(a1, b1, c1, d1, e1), overview = g2.overview.copy(reachability = reachability3))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g1))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g2))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g3))

      checkSerialization(GossipStatus(a1.uniqueAddress, g1.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g2.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g3.version))

      checkSerialization(InternalClusterAction.Welcome(uniqueAddress, g2))

      val mg = MetricsGossip(Set(
        NodeMetrics(a1.address, 4711, Set(Metric("foo", 1.2, None))),
        NodeMetrics(b1.address, 4712, Set(
          Metric("foo", 2.1, Some(EWMA(value = 100.0, alpha = 0.18))),
          Metric("bar1", Double.MinPositiveValue, None),
          Metric("bar2", Float.MaxValue, None),
          Metric("bar3", Int.MaxValue, None),
          Metric("bar4", Long.MaxValue, None),
          Metric("bar5", BigInt(Long.MaxValue), None)))))
      checkSerialization(MetricsGossipEnvelope(a1.address, mg, true))
    }
  }
}
