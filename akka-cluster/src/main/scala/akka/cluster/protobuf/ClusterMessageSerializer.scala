/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.protobuf

import akka.serialization.Serializer
import akka.cluster._
import scala.collection.breakOut
import akka.actor.{ ExtendedActorSystem, Address }
import scala.Some
import scala.collection.immutable
import java.io.{ ByteArrayInputStream, ObjectOutputStream, ByteArrayOutputStream }
import com.google.protobuf.ByteString
import akka.util.ClassLoaderObjectInputStream
import java.{ lang ⇒ jl }

class ClusterMessageSerializer(val system: ExtendedActorSystem) extends Serializer {

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ClusterMessage], Array[Byte] ⇒ AnyRef](
    classOf[InternalClusterAction.Join] -> {
      case bytes ⇒
        val m = msg.Join.defaultInstance.mergeFrom(bytes)
        InternalClusterAction.Join(uniqueAddressFromProto(m.node), m.roles.toSet)
    },
    classOf[InternalClusterAction.Welcome] -> {
      case bytes ⇒
        val m = msg.Welcome.defaultInstance.mergeFrom(bytes)
        InternalClusterAction.Welcome(uniqueAddressFromProto(m.from), gossipFromProto(m.gossip))
    },
    classOf[ClusterUserAction.Leave] -> (bytes ⇒ ClusterUserAction.Leave(addressFromBinary(bytes))),
    classOf[ClusterUserAction.Down] -> (bytes ⇒ ClusterUserAction.Down(addressFromBinary(bytes))),
    InternalClusterAction.InitJoin.getClass -> (_ ⇒ InternalClusterAction.InitJoin),
    classOf[InternalClusterAction.InitJoinAck] -> (bytes ⇒ InternalClusterAction.InitJoinAck(addressFromBinary(bytes))),
    classOf[InternalClusterAction.InitJoinNack] -> (bytes ⇒ InternalClusterAction.InitJoinNack(addressFromBinary(bytes))),
    classOf[ClusterLeaderAction.Exit] -> (bytes ⇒ ClusterLeaderAction.Exit(uniqueAddressFromBinary(bytes))),
    classOf[ClusterLeaderAction.Shutdown] -> (bytes ⇒ ClusterLeaderAction.Shutdown(uniqueAddressFromBinary(bytes))),
    classOf[ClusterHeartbeatReceiver.Heartbeat] -> (bytes ⇒ ClusterHeartbeatReceiver.Heartbeat(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatReceiver.EndHeartbeat] -> (bytes ⇒ ClusterHeartbeatReceiver.EndHeartbeat(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatSender.HeartbeatRequest] -> (bytes ⇒ ClusterHeartbeatSender.HeartbeatRequest(addressFromBinary(bytes))),
    classOf[GossipEnvelope] -> gossipEnvelopeFromBinary,
    classOf[MetricsGossipEnvelope] -> metricsGossipEnvelopeFromBinary)

  def includeManifest: Boolean = true

  def identifier = 5

  def toBinary(obj: AnyRef): Array[Byte] = (obj match {
    case ClusterHeartbeatReceiver.Heartbeat(from) ⇒
      addressToProto(from)
    case m: GossipEnvelope ⇒
      gossipEnvelopeToProto(m)
    case m: MetricsGossipEnvelope ⇒
      metricsGossipEnvelopeToProto(m)
    case InternalClusterAction.Join(node, roles) ⇒
      msg.Join(uniqueAddressToProto(node), roles.map(identity)(breakOut): Vector[String])
    case InternalClusterAction.Welcome(from, gossip) ⇒
      msg.Welcome(uniqueAddressToProto(from), gossipToProto(gossip))
    case ClusterUserAction.Leave(address) ⇒
      addressToProto(address)
    case ClusterUserAction.Down(address) ⇒
      addressToProto(address)
    case InternalClusterAction.InitJoin ⇒
      msg.Empty()
    case InternalClusterAction.InitJoinAck(address) ⇒
      addressToProto(address)
    case InternalClusterAction.InitJoinNack(address) ⇒
      addressToProto(address)
    case ClusterLeaderAction.Exit(node) ⇒
      uniqueAddressToProto(node)
    case ClusterLeaderAction.Shutdown(node) ⇒
      uniqueAddressToProto(node)
    case ClusterHeartbeatReceiver.EndHeartbeat(from) ⇒
      addressToProto(from)
    case ClusterHeartbeatSender.HeartbeatRequest(from) ⇒
      addressToProto(from)
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
  }).toByteArray

  def fromBinary(bytes: Array[Byte],
                 clazz: Option[Class[_]]): AnyRef = {
    clazz match {
      case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ClusterMessage]]) match {
        case Some(f) ⇒ f(bytes)
        case None    ⇒ throw new IllegalArgumentException(s"Unimplemented deserialization of message class ${c} in ClusterSerializer")
      }
      case _ ⇒ throw new IllegalArgumentException("Need a cluster message class to be able to deserialize bytes in ClusterSerializer")
    }
  }

  private def addressFromBinary(bytes: Array[Byte]): Address = {
    addressFromProto(msg.Address.defaultInstance.mergeFrom(bytes))
  }

  private def uniqueAddressFromBinary(bytes: Array[Byte]): UniqueAddress = {
    uniqueAddressFromProto(msg.UniqueAddress.defaultInstance.mergeFrom(bytes))
  }

  private def addressToProto(address: Address): msg.Address = {
    msg.Address(address.system, address.host.getOrElse(""), address.port.getOrElse(0), Some(address.protocol))
  }

  private def uniqueAddressToProto(uniqueAddress: UniqueAddress): msg.UniqueAddress = {
    msg.UniqueAddress(addressToProto(uniqueAddress.address), uniqueAddress.uid)
  }

  private def addressFromProto(address: msg.Address): Address = {
    Address(address.protocol.getOrElse(""), address.system, address.hostname, address.port)
  }

  private def uniqueAddressFromProto(uniqueAddress: msg.UniqueAddress): UniqueAddress = {
    UniqueAddress(addressFromProto(uniqueAddress.address), uniqueAddress.uid)
  }

  private val memberStatusToInt = scala.collection.immutable.HashMap[MemberStatus, Int](
    MemberStatus.Joining -> msg.MemberStatus.Joining_VALUE,
    MemberStatus.Up -> msg.MemberStatus.Up_VALUE,
    MemberStatus.Leaving -> msg.MemberStatus.Leaving_VALUE,
    MemberStatus.Exiting -> msg.MemberStatus.Exiting_VALUE,
    MemberStatus.Down -> msg.MemberStatus.Down_VALUE,
    MemberStatus.Removed -> msg.MemberStatus.Removed_VALUE)

  private val memberStatusFromInt = memberStatusToInt.map { case (a, b) ⇒ (b, a) }

  private def mapWithErrorMessage[T](map: Map[T, Int], value: T, unknown: String): Int = map.get(value) match {
    case Some(x) ⇒ x
    case _       ⇒ throw new IllegalArgumentException(s"Unknown ${unknown} [${value}] in cluster message")
  }

  private def gossipToProto(gossip: Gossip): msg.Gossip = {
    val allMembers = List(gossip.members, gossip.overview.unreachable).flatMap(identity)
    val allAddresses = allMembers.map(_.uniqueAddress).to[Vector]
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allRoles = allMembers.flatMap(_.roles).to[Vector]
    val roleMapping = allRoles.zipWithIndex.toMap
    val allHashes = gossip.overview.seen.values.foldLeft(gossip.version.versions.keys.map(_.hash).toSet) {
      case (s, VectorClock(t, v)) ⇒ s ++ v.keys.map(_.hash)
    }.to[Vector]
    val hashMapping = allHashes.zipWithIndex.toMap

    def mapUniqueAddress(uniqueAddress: UniqueAddress) = mapWithErrorMessage(addressMapping, uniqueAddress, "address")
    def mapRole(role: String) = mapWithErrorMessage(roleMapping, role, "role")
    def mapHash(hash: String) = mapWithErrorMessage(hashMapping, hash, "hash")

    def memberToProto(member: Member) = {
      msg.Member(mapUniqueAddress(member.uniqueAddress), msg.MemberStatus.valueOf(memberStatusToInt(member.status)), member.roles.map(mapRole).to[Vector])
    }

    def vectorClockToProto(version: VectorClock) = {
      msg.VectorClock(version.timestamp.time,
        version.versions.map { case (n, t) ⇒ msg.VectorClock.Version(mapHash(n.hash), t.time) }.to[Vector])
    }

    def seenToProto(seen: (UniqueAddress, VectorClock)) = seen match {
      case (address: UniqueAddress, version: VectorClock) ⇒
        msg.GossipOverview.Seen(mapUniqueAddress(address), vectorClockToProto(version))
    }

    val unreachable = gossip.overview.unreachable.map(memberToProto).to[Vector]
    val members = gossip.members.toSeq.map(memberToProto).to[Vector]
    val seen = gossip.overview.seen.map(seenToProto).to[Vector]

    val overview = msg.GossipOverview(seen, unreachable)

    msg.Gossip(allAddresses.map(uniqueAddressToProto),
      allRoles, allHashes, members, overview, vectorClockToProto(gossip.version))
  }

  private def gossipEnvelopeToProto(envelope: GossipEnvelope): msg.GossipEnvelope = {
    msg.GossipEnvelope(uniqueAddressToProto(envelope.from), uniqueAddressToProto(envelope.to),
      gossipToProto(envelope.gossip), envelope.conversation)
  }

  private def gossipEnvelopeFromBinary(bytes: Array[Byte]): GossipEnvelope = {
    gossipEnvelopeFromProto(msg.GossipEnvelope.defaultInstance.mergeFrom(bytes))
  }

  private def gossipFromProto(gossip: msg.Gossip): Gossip = {
    val addressMapping = gossip.allAddresses.map(uniqueAddressFromProto)
    val roleMapping = gossip.allRoles
    val hashMapping = gossip.allHashes

    def memberFromProto(member: msg.Member) = {
      new Member(addressMapping(member.addressIndex), memberStatusFromInt(member.status.id),
        member.rolesIndexes.map(roleMapping).to[Set])
    }

    def vectorClockFromProto(version: msg.VectorClock) = {
      VectorClock(VectorClock.Timestamp(version.timestamp),
        version.versions.map {
          case msg.VectorClock.Version(h, t) ⇒
            (VectorClock.Node.fromHash(hashMapping(h)), VectorClock.Timestamp(t))
        }.toMap)
    }

    def seenFromProto(seen: msg.GossipOverview.Seen) =
      (addressMapping(seen.addressIndex), vectorClockFromProto(seen.version))

    val members = gossip.members.map(memberFromProto).to[immutable.SortedSet]
    val unreachable = gossip.overview.unreachable.map(memberFromProto).toSet
    val seen = gossip.overview.seen.map(seenFromProto).toMap
    val overview = GossipOverview(seen, unreachable)

    Gossip(members, overview, vectorClockFromProto(gossip.version))
  }

  private def gossipEnvelopeFromProto(envelope: msg.GossipEnvelope): GossipEnvelope = {
    GossipEnvelope(uniqueAddressFromProto(envelope.from), uniqueAddressFromProto(envelope.to),
      gossipFromProto(envelope.gossip))
  }

  private def metricsGossipEnvelopeToProto(envelope: MetricsGossipEnvelope): msg.MetricsGossipEnvelope = {
    val mgossip = envelope.gossip
    val allAddresses = mgossip.nodes.foldLeft(Set.empty[Address])((s, n) ⇒ s + n.address).to[Vector]
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allMetricNames = mgossip.nodes.foldLeft(Set.empty[String])((s, n) ⇒ s ++ n.metrics.map(_.name)).to[Vector]
    val metricNamesMapping = allMetricNames.zipWithIndex.toMap

    def mapAddress(address: Address) = mapWithErrorMessage(addressMapping, address, "address")
    def mapName(name: String) = mapWithErrorMessage(metricNamesMapping, name, "address")

    def ewmaToProto(ewma: Option[EWMA]): Option[msg.NodeMetrics.EWMA] = {
      ewma.map(x ⇒ msg.NodeMetrics.EWMA(x.value, x.alpha))
    }

    def numberToProto(number: Number): msg.NodeMetrics.Number = {
      import msg.NodeMetrics.Number
      import msg.NodeMetrics.NumberType
      number match {
        case n: jl.Double  ⇒ Number(NumberType.Double, None, Some(jl.Double.doubleToLongBits(n)), None)
        case n: jl.Long    ⇒ Number(NumberType.Long, None, Some(n), None)
        case n: jl.Float   ⇒ Number(NumberType.Float, Some(jl.Float.floatToIntBits(n)), None, None)
        case n: jl.Integer ⇒ Number(NumberType.Integer, Some(n), None, None)
        case _ ⇒
          val bos = new ByteArrayOutputStream
          val out = new ObjectOutputStream(bos)
          out.writeObject(number)
          out.close()
          msg.NodeMetrics.Number(msg.NodeMetrics.NumberType.Serialized, None, None,
            Some(ByteString.copyFrom(bos.toByteArray)))
      }
    }

    def metricToProto(metric: Metric): msg.NodeMetrics.Metric = {
      msg.NodeMetrics.Metric(mapName(metric.name), numberToProto(metric.value), ewmaToProto(metric.average))
    }

    def nodeMetricsToProto(nodeMetrics: NodeMetrics): msg.NodeMetrics = {
      msg.NodeMetrics(mapAddress(nodeMetrics.address), nodeMetrics.timestamp,
        nodeMetrics.metrics.map(metricToProto).to[Vector])
    }

    val nodeMetrics = mgossip.nodes.map(nodeMetricsToProto).to[Vector]

    msg.MetricsGossipEnvelope(addressToProto(envelope.from),
      msg.MetricsGossip(allAddresses.map(addressToProto), allMetricNames, nodeMetrics), envelope.reply)
  }

  private def metricsGossipEnvelopeFromBinary(bytes: Array[Byte]): MetricsGossipEnvelope = {
    metricsGossipEnvelopeFromProto(msg.MetricsGossipEnvelope.defaultInstance.mergeFrom(bytes))
  }

  private def metricsGossipEnvelopeFromProto(envelope: msg.MetricsGossipEnvelope): MetricsGossipEnvelope = {
    val mgossip = envelope.gossip
    val addressMapping = mgossip.allAddresses.map(addressFromProto)
    val metricNameMapping = mgossip.allMetricNames

    def ewmaFromProto(ewma: Option[msg.NodeMetrics.EWMA]): Option[EWMA] = {
      ewma.map(x ⇒ EWMA(x.value, x.alpha))
    }

    def numberFromProto(number: msg.NodeMetrics.Number): Number = {
      import msg.NodeMetrics.Number
      import msg.NodeMetrics.NumberType
      number match {
        case Number(NumberType.Double, _, Some(n), _)  ⇒ jl.Double.longBitsToDouble(n)
        case Number(NumberType.Long, _, Some(n), _)    ⇒ n
        case Number(NumberType.Float, Some(n), _, _)   ⇒ jl.Float.intBitsToFloat(n)
        case Number(NumberType.Integer, Some(n), _, _) ⇒ n
        case Number(NumberType.Serialized, _, _, Some(b)) ⇒
          val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader,
            new ByteArrayInputStream(b.toByteArray))
          val obj = in.readObject
          in.close()
          obj.asInstanceOf[jl.Number]
      }
    }

    def metricFromProto(metric: msg.NodeMetrics.Metric): Metric = {
      Metric(metricNameMapping(metric.nameIndex), numberFromProto(metric.number), ewmaFromProto(metric.ewma))
    }

    def nodeMetricsFromProto(nodeMetrics: msg.NodeMetrics): NodeMetrics = {
      NodeMetrics(addressMapping(nodeMetrics.addressIndex), nodeMetrics.timestamp,
        nodeMetrics.metrics.map(metricFromProto).toSet)
    }

    val nodeMetrics = mgossip.nodeMetrics.map(nodeMetricsFromProto).toSet

    MetricsGossipEnvelope(addressFromProto(envelope.from),
      MetricsGossip(nodeMetrics), envelope.reply)
  }

}
