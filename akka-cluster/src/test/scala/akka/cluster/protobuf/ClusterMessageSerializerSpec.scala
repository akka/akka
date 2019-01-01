/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.protobuf

import akka.cluster._
import akka.actor.{ ActorSystem, Address, ExtendedActorSystem }
import akka.cluster.InternalClusterAction.CompatibleConfig
import akka.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import akka.routing.RoundRobinPool

import collection.immutable.SortedSet
import akka.testkit.{ AkkaSpec, TestKit }
import com.typesafe.config.ConfigFactory

class ClusterMessageSerializerSpec extends AkkaSpec(
  "akka.actor.provider = cluster") {

  val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

  def roundtrip[T <: AnyRef](obj: T): T = {
    val manifest = serializer.manifest(obj)
    val blob = serializer.toBinary(obj)
    serializer.fromBinary(blob, manifest).asInstanceOf[T]
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

  val a1 = TestMember(Address("akka.tcp", "sys", "a", 2552), Joining, Set.empty[String])
  val b1 = TestMember(Address("akka.tcp", "sys", "b", 2552), Up, Set("r1"))
  val c1 = TestMember(Address("akka.tcp", "sys", "c", 2552), Leaving, Set.empty[String], "foo")
  val d1 = TestMember(Address("akka.tcp", "sys", "d", 2552), Exiting, Set("r1"), "foo")
  val e1 = TestMember(Address("akka.tcp", "sys", "e", 2552), Down, Set("r3"))
  val f1 = TestMember(Address("akka.tcp", "sys", "f", 2552), Removed, Set("r3"), "foo")

  "ClusterMessages" must {

    "be serializable" in {
      val address = Address("akka.tcp", "system", "some.host.org", 4711)
      val uniqueAddress = UniqueAddress(address, 17L)
      val address2 = Address("akka.tcp", "system", "other.host.org", 4711)
      val uniqueAddress2 = UniqueAddress(address2, 18L)
      checkSerialization(InternalClusterAction.Join(uniqueAddress, Set("foo", "bar", "dc-A")))
      checkSerialization(ClusterUserAction.Leave(address))
      checkSerialization(ClusterUserAction.Down(address))
      checkSerialization(InternalClusterAction.InitJoin(ConfigFactory.empty))
      checkSerialization(InternalClusterAction.InitJoinAck(address, CompatibleConfig(ConfigFactory.empty)))
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
      val g4 = g1.remove(d1.uniqueAddress, 352684800)
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g1))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g2))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g3))
      checkSerialization(GossipEnvelope(a1.uniqueAddress, uniqueAddress2, g4))

      checkSerialization(GossipStatus(a1.uniqueAddress, g1.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g2.version))
      checkSerialization(GossipStatus(a1.uniqueAddress, g3.version))

      checkSerialization(InternalClusterAction.Welcome(uniqueAddress, g2))
    }

    "be compatible with wire format of version 2.5.9 (using InitJoin singleton instead of class)" in {
      // we must use the old singleton class name so that the other side will see an InitJoin
      // but discard the config as it does not know about the config check
      val oldClassName = "akka.cluster.InternalClusterAction$InitJoin$"
      serializer.manifest(InternalClusterAction.InitJoin(ConfigFactory.empty())) should ===(oldClassName)

      // in 2.5.9 and earlier, it was an object and serialized to empty byte array
      // and we should accept that
      val deserialized = serializer.fromBinary(Array.emptyByteArray, oldClassName)
      deserialized shouldBe an[InternalClusterAction.InitJoin]
    }

    "deserialize from wire format of version 2.5.9 (using serialized address for InitJoinAck)" in {
      // we must use the old singleton class name so that the other side will see an InitJoin
      // but discard the config as it does not know about the config check
      val initJoinAck = InternalClusterAction.InitJoinAck(
        Address("akka.tcp", "cluster", "127.0.0.1", 2552),
        InternalClusterAction.UncheckedConfig)
      val serializedInitJoinAckPre2510 = serializer.addressToProto(initJoinAck.address).build().toByteArray

      val deserialized = serializer.fromBinary(serializedInitJoinAckPre2510, ClusterMessageSerializer.InitJoinAckManifest)
      deserialized shouldEqual initJoinAck
    }

    "serialize to wire format of version 2.5.9 (using serialized address for InitJoinAck)" in {
      val initJoinAck = InternalClusterAction.InitJoinAck(
        Address("akka.tcp", "cluster", "127.0.0.1", 2552),
        InternalClusterAction.ConfigCheckUnsupportedByJoiningNode)
      val bytes = serializer.toBinary(initJoinAck)

      val expectedSerializedInitJoinAckPre2510 = serializer.addressToProto(initJoinAck.address).build().toByteArray
      bytes.toList should ===(expectedSerializedInitJoinAckPre2510.toList)
    }

    "be compatible with wire format of version 2.5.3 (using use-role instead of use-roles)" in {
      val system = ActorSystem("ClusterMessageSerializer-old-wire-format")

      try {
        val serializer = new ClusterMessageSerializer(system.asInstanceOf[ExtendedActorSystem])

        // the oldSnapshot was created with the version of ClusterRouterPoolSettings in Akka 2.5.3. See issue #23257.
        // It was created with:
        /*
          import org.apache.commons.codec.binary.Hex.encodeHex
          val bytes = serializer.toBinary(
            ClusterRouterPool(RoundRobinPool(nrOfInstances = 4), ClusterRouterPoolSettings(123, 345, true, Some("role ABC"))))
          println(String.valueOf(encodeHex(bytes)))
        */

        val oldBytesHex = "0a0f08101205524f5252501a04080418001211087b10d90218012208726f6c6520414243"

        import org.apache.commons.codec.binary.Hex.decodeHex
        val oldBytes = decodeHex(oldBytesHex.toCharArray)
        val result = serializer.fromBinary(oldBytes, classOf[ClusterRouterPool])

        result match {
          case pool: ClusterRouterPool ⇒
            pool.settings.totalInstances should ===(123)
            pool.settings.maxInstancesPerNode should ===(345)
            pool.settings.allowLocalRoutees should ===(true)
            pool.settings.useRole should ===(Some("role ABC"))
            pool.settings.useRoles should ===(Set("role ABC"))
        }
      } finally {
        TestKit.shutdownActorSystem(system)
      }

    }

    "add a default data center role to gossip if none is present" in {
      val env = roundtrip(GossipEnvelope(a1.uniqueAddress, d1.uniqueAddress, Gossip(SortedSet(a1, d1))))
      env.gossip.members.head.roles should be(Set(ClusterSettings.DcRolePrefix + "default"))
      env.gossip.members.tail.head.roles should be(Set("r1", ClusterSettings.DcRolePrefix + "foo"))
    }

    "add a default data center role to internal join action if none is present" in {
      val join = roundtrip(InternalClusterAction.Join(a1.uniqueAddress, Set()))
      join.roles should be(Set(ClusterSettings.DcRolePrefix + "default"))
    }
  }
  "Cluster router pool" must {
    "be serializable with no role" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true
        )
      ))
    }

    "be serializable with one role" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4
        ),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true,
          useRoles = Set("Richard, Duke of Gloucester")
        )
      ))
    }

    "be serializable with many roles" in {
      checkSerialization(ClusterRouterPool(
        RoundRobinPool(
          nrOfInstances = 4),
        ClusterRouterPoolSettings(
          totalInstances = 2,
          maxInstancesPerNode = 5,
          allowLocalRoutees = true,
          useRoles = Set("Richard, Duke of Gloucester", "Hongzhi Emperor", "Red Rackham")
        )
      ))
    }
  }

}
