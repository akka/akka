/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.protobuf

import akka.cluster._
import akka.actor.{ Address, ExtendedActorSystem }
import akka.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import akka.routing.{ DefaultOptimalSizeExploringResizer, RoundRobinPool }

import collection.immutable.SortedSet
import akka.testkit.AkkaSpec

class ClusterMessageSerializerSpec extends AkkaSpec(
  "akka.actor.provider = cluster") {

  val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def roundtrip[T <: AnyRef](obj: T): T = {
    val blob = serializer.toBinary(obj)
    serializer.fromBinary(blob, obj.getClass).asInstanceOf[T]
  }

  def checkSerialization(obj: AnyRef): Unit = {
    (obj, roundtrip(obj)) match {
      case (env: GossipEnvelope, env2: GossipEnvelope) ⇒
        env2.from should ===(env.from)
        env2.to should ===(env.to)
        env2.gossip should ===(env.gossip)
      case (_, ref) ⇒
        ref should ===(obj)
    }

  }

  import MemberStatus._

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Joining, Set.empty)
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, Set("r1"))
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving, Set("team-foo"))
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Exiting, Set("r1", "team-foo"))
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Down, Set("r3"))
  val f1 = TestMember(Address("akka.tcp", "sys", "f", 2552), Removed, Set("team-foo", "r3"))

  "ClusterMessages" must {

    "be serializable" in {
      val address = Address("akka.tcp", "system", "some.host.org", 4711)
      val uniqueAddress = UniqueAddress(address, 17L)
      val address2 = Address("akka.tcp", "system", "other.host.org", 4711)
      val uniqueAddress2 = UniqueAddress(address2, 18L)
      checkSerialization(InternalClusterAction.Join(uniqueAddress, Set("foo", "bar")))
      checkSerialization(ClusterUserAction.Leave(address))
      checkSerialization(ClusterUserAction.Down(address))
      checkSerialization(InternalClusterAction.InitJoin)
      checkSerialization(InternalClusterAction.InitJoinAck(address))
      checkSerialization(InternalClusterAction.InitJoinNack(address))
      checkSerialization(ClusterHeartbeatSender.Heartbeat(address))
      checkSerialization(ClusterHeartbeatSender.HeartbeatRsp(uniqueAddress))
      checkSerialization(InternalClusterAction.ExitingConfirmed(uniqueAddress))

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
    }

    "add a default team role if none is present" in {
      val env = roundtrip(GossipEnvelope(a1.uniqueAddress, d1.uniqueAddress, Gossip(SortedSet(a1, d1))))
      env.gossip.members.head.roles should be(Set("team-default"))
      env.gossip.members.tail.head.roles should be(Set("r1", "team-foo"))
    }
  }
  "Cluster router pool" must {
    "be serializable" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true,
          useRole = Some("Richard, Duke of Gloucester")
        )
      ))
    }
  }

}
