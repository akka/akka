/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.protobuf

import akka.serialization.Serializer
import akka.cluster._
import scala.collection.breakOut
import scala.collection.immutable.TreeMap
import akka.actor.{ ExtendedActorSystem, Address }
import scala.Some
import scala.collection.immutable
import java.io.{ ByteArrayInputStream, ObjectOutputStream, ByteArrayOutputStream }
import com.google.protobuf.ByteString
import akka.util.ClassLoaderObjectInputStream
import java.{ lang ⇒ jl }
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import com.google.protobuf.MessageLite
import scala.annotation.tailrec

/**
 * Protobuf serializer of cluster messages.
 */
class ClusterMessageSerializer(val system: ExtendedActorSystem) extends Serializer {

  private final val BufferSize = 1024 * 4

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ClusterMessage], Array[Byte] ⇒ AnyRef](
    classOf[InternalClusterAction.Join] -> {
      case bytes ⇒
        val m = msg.Join.defaultInstance.mergeFrom(bytes)
        InternalClusterAction.Join(uniqueAddressFromProto(m.node), m.roles.toSet)
    },
    classOf[InternalClusterAction.Welcome] -> {
      case bytes ⇒
        val m = msg.Welcome.defaultInstance.mergeFrom(decompress(bytes))
        InternalClusterAction.Welcome(uniqueAddressFromProto(m.from), gossipFromProto(m.gossip))
    },
    classOf[ClusterUserAction.Leave] -> (bytes ⇒ ClusterUserAction.Leave(addressFromBinary(bytes))),
    classOf[ClusterUserAction.Down] -> (bytes ⇒ ClusterUserAction.Down(addressFromBinary(bytes))),
    InternalClusterAction.InitJoin.getClass -> (_ ⇒ InternalClusterAction.InitJoin),
    classOf[InternalClusterAction.InitJoinAck] -> (bytes ⇒ InternalClusterAction.InitJoinAck(addressFromBinary(bytes))),
    classOf[InternalClusterAction.InitJoinNack] -> (bytes ⇒ InternalClusterAction.InitJoinNack(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatReceiver.Heartbeat] -> (bytes ⇒ ClusterHeartbeatReceiver.Heartbeat(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatReceiver.EndHeartbeat] -> (bytes ⇒ ClusterHeartbeatReceiver.EndHeartbeat(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatReceiver.EndHeartbeatAck] -> (bytes ⇒ ClusterHeartbeatReceiver.EndHeartbeatAck(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatSender.HeartbeatRequest] -> (bytes ⇒ ClusterHeartbeatSender.HeartbeatRequest(addressFromBinary(bytes))),
    classOf[GossipStatus] -> gossipStatusFromBinary,
    classOf[GossipEnvelope] -> gossipEnvelopeFromBinary,
    classOf[MetricsGossipEnvelope] -> metricsGossipEnvelopeFromBinary)

  def includeManifest: Boolean = true

  def identifier = 5

  def toBinary(obj: AnyRef): Array[Byte] =
    obj match {
      case ClusterHeartbeatReceiver.Heartbeat(from) ⇒ addressToProto(from).toByteArray
      case m: GossipEnvelope ⇒ compress(gossipEnvelopeToProto(m))
      case m: GossipStatus ⇒ gossipStatusToProto(m).toByteArray
      case m: MetricsGossipEnvelope ⇒ compress(metricsGossipEnvelopeToProto(m))
      case InternalClusterAction.Join(node, roles) ⇒ msg.Join(uniqueAddressToProto(node), roles.toVector).toByteArray
      case InternalClusterAction.Welcome(from, gossip) ⇒ compress(msg.Welcome(uniqueAddressToProto(from), gossipToProto(gossip)))
      case ClusterUserAction.Leave(address) ⇒ addressToProto(address).toByteArray
      case ClusterUserAction.Down(address) ⇒ addressToProto(address).toByteArray
      case InternalClusterAction.InitJoin ⇒ msg.Empty().toByteArray
      case InternalClusterAction.InitJoinAck(address) ⇒ addressToProto(address).toByteArray
      case InternalClusterAction.InitJoinNack(address) ⇒ addressToProto(address).toByteArray
      case ClusterHeartbeatReceiver.EndHeartbeat(from) ⇒ addressToProto(from).toByteArray
      case ClusterHeartbeatReceiver.EndHeartbeatAck(from) ⇒ addressToProto(from).toByteArray
      case ClusterHeartbeatSender.HeartbeatRequest(from) ⇒ addressToProto(from).toByteArray
      case _ ⇒ throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
    }

  def compress(msg: MessageLite): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    msg.writeTo(zip)
    zip.close()
    bos.toByteArray
  }

  def decompress(bytes: Array[Byte]): Array[Byte] = {
    val in = new GZIPInputStream(new ByteArrayInputStream(bytes))
    val out = new ByteArrayOutputStream()
    val buffer = new Array[Byte](BufferSize)

    @tailrec def readChunk(): Unit = in.read(buffer) match {
      case -1 ⇒ ()
      case n ⇒
        out.write(buffer, 0, n)
        readChunk()
    }

    readChunk()
    out.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef =
    clazz match {
      case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ClusterMessage]]) match {
        case Some(f) ⇒ f(bytes)
        case None    ⇒ throw new IllegalArgumentException(s"Unimplemented deserialization of message class $c in ClusterSerializer")
      }
      case _ ⇒ throw new IllegalArgumentException("Need a cluster message class to be able to deserialize bytes in ClusterSerializer")
    }

  private def addressFromBinary(bytes: Array[Byte]): Address =
    addressFromProto(msg.Address.defaultInstance.mergeFrom(bytes))

  private def uniqueAddressFromBinary(bytes: Array[Byte]): UniqueAddress =
    uniqueAddressFromProto(msg.UniqueAddress.defaultInstance.mergeFrom(bytes))

  private def addressToProto(address: Address): msg.Address =
    msg.Address(address.system, address.host.getOrElse(""), address.port.getOrElse(0), Some(address.protocol))

  private def uniqueAddressToProto(uniqueAddress: UniqueAddress): msg.UniqueAddress =
    msg.UniqueAddress(addressToProto(uniqueAddress.address), uniqueAddress.uid)

  private def addressFromProto(address: msg.Address): Address =
    Address(address.protocol.getOrElse(""), address.system, address.hostname, address.port)

  private def uniqueAddressFromProto(uniqueAddress: msg.UniqueAddress): UniqueAddress =
    UniqueAddress(addressFromProto(uniqueAddress.address), uniqueAddress.uid)

  private val memberStatusToInt = scala.collection.immutable.HashMap[MemberStatus, Int](
    MemberStatus.Joining -> msg.MemberStatus.Joining_VALUE,
    MemberStatus.Up -> msg.MemberStatus.Up_VALUE,
    MemberStatus.Leaving -> msg.MemberStatus.Leaving_VALUE,
    MemberStatus.Exiting -> msg.MemberStatus.Exiting_VALUE,
    MemberStatus.Down -> msg.MemberStatus.Down_VALUE,
    MemberStatus.Removed -> msg.MemberStatus.Removed_VALUE)

  private val memberStatusFromInt = memberStatusToInt.map { case (a, b) ⇒ (b, a) }

  private val reachabilityStatusToInt = scala.collection.immutable.HashMap[Reachability.ReachabilityStatus, Int](
    Reachability.Reachable -> msg.ReachabilityStatus.Reachable_VALUE,
    Reachability.Unreachable -> msg.ReachabilityStatus.Unreachable_VALUE,
    Reachability.Terminated -> msg.ReachabilityStatus.Terminated_VALUE)

  private val reachabilityStatusFromInt = reachabilityStatusToInt.map { case (a, b) ⇒ (b, a) }

  private def mapWithErrorMessage[T](map: Map[T, Int], value: T, unknown: String): Int = map.get(value) match {
    case Some(x) ⇒ x
    case _       ⇒ throw new IllegalArgumentException(s"Unknown ${unknown} [${value}] in cluster message")
  }

  private def gossipToProto(gossip: Gossip): msg.Gossip = {
    import scala.collection.breakOut
    val allMembers = gossip.members.toVector
    val allAddresses: Vector[UniqueAddress] = allMembers.map(_.uniqueAddress)
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allRoles = allMembers.foldLeft(Set.empty[String])((acc, m) ⇒ acc ++ m.roles).to[Vector]
    val roleMapping = allRoles.zipWithIndex.toMap
    val allHashes = gossip.version.versions.keys.to[Vector]
    val hashMapping = allHashes.zipWithIndex.toMap

    def mapUniqueAddress(uniqueAddress: UniqueAddress) = mapWithErrorMessage(addressMapping, uniqueAddress, "address")
    def mapRole(role: String) = mapWithErrorMessage(roleMapping, role, "role")

    def memberToProto(member: Member) =
      msg.Member(mapUniqueAddress(member.uniqueAddress), member.upNumber,
        msg.MemberStatus.valueOf(memberStatusToInt(member.status)), member.roles.map(mapRole)(breakOut))

    def reachabilityToProto(reachability: Reachability): Vector[msg.ObserverReachability] = {
      reachability.versions.map {
        case (observer, version) ⇒
          val subjectReachability = reachability.recordsFrom(observer).map(r ⇒
            msg.SubjectReachability(mapUniqueAddress(r.subject),
              msg.ReachabilityStatus.valueOf(reachabilityStatusToInt(r.status)), r.version))
          msg.ObserverReachability(mapUniqueAddress(observer), version, subjectReachability)
      }(breakOut)
    }

    val reachability = reachabilityToProto(gossip.overview.reachability)
    val members: Vector[msg.Member] = gossip.members.map(memberToProto)(breakOut)
    val seen: Vector[Int] = gossip.overview.seen.map(mapUniqueAddress)(breakOut)

    val overview = msg.GossipOverview(seen, reachability)

    msg.Gossip(allAddresses.map(uniqueAddressToProto),
      allRoles, allHashes, members, overview, vectorClockToProto(gossip.version, hashMapping))
  }

  private def vectorClockToProto(version: VectorClock, hashMapping: Map[String, Int]): msg.VectorClock = {
    val versions: Vector[msg.VectorClock.Version] = version.versions.map({
      case (n, t) ⇒ msg.VectorClock.Version(mapWithErrorMessage(hashMapping, n, "hash"), t.time)
    })(breakOut)
    msg.VectorClock(None, versions)
  }

  private def gossipEnvelopeToProto(envelope: GossipEnvelope): msg.GossipEnvelope =
    msg.GossipEnvelope(uniqueAddressToProto(envelope.from), uniqueAddressToProto(envelope.to), gossipToProto(envelope.gossip))

  private def gossipStatusToProto(status: GossipStatus): msg.GossipStatus = {
    val allHashes = status.version.versions.keys.toVector
    val hashMapping = allHashes.zipWithIndex.toMap
    msg.GossipStatus(uniqueAddressToProto(status.from), allHashes, vectorClockToProto(status.version, hashMapping))
  }

  private def gossipEnvelopeFromBinary(bytes: Array[Byte]): GossipEnvelope =
    gossipEnvelopeFromProto(msg.GossipEnvelope.defaultInstance.mergeFrom(decompress(bytes)))

  private def gossipStatusFromBinary(bytes: Array[Byte]): GossipStatus =
    gossipStatusFromProto(msg.GossipStatus.defaultInstance.mergeFrom(bytes))

  private def gossipFromProto(gossip: msg.Gossip): Gossip = {
    import scala.collection.breakOut
    val addressMapping = gossip.allAddresses.map(uniqueAddressFromProto)
    val roleMapping = gossip.allRoles
    val hashMapping = gossip.allHashes

    def reachabilityFromProto(observerReachability: immutable.Seq[msg.ObserverReachability]): Reachability = {
      val recordBuilder = new immutable.VectorBuilder[Reachability.Record]
      val versionsBuilder = new scala.collection.mutable.MapBuilder[UniqueAddress, Long, Map[UniqueAddress, Long]](Map.empty)
      for (o ← observerReachability) {
        val observer = addressMapping(o.addressIndex)
        versionsBuilder += ((observer, o.version))
        for (s ← o.subjectReachability) {
          val subject = addressMapping(s.addressIndex)
          val record = Reachability.Record(observer, subject, reachabilityStatusFromInt(s.status), s.version)
          recordBuilder += record
        }
      }

      Reachability.create(recordBuilder.result(), versionsBuilder.result())
    }

    def memberFromProto(member: msg.Member) =
      new Member(addressMapping(member.addressIndex), member.upNumber, memberStatusFromInt(member.status.id),
        member.rolesIndexes.map(roleMapping)(breakOut))

    val members: immutable.SortedSet[Member] = gossip.members.map(memberFromProto)(breakOut)

    val reachability = reachabilityFromProto(gossip.overview.observerReachability)
    val seen: Set[UniqueAddress] = gossip.overview.seen.map(addressMapping)(breakOut)
    val overview = GossipOverview(seen, reachability)

    Gossip(members, overview, vectorClockFromProto(gossip.version, hashMapping))
  }

  private def vectorClockFromProto(version: msg.VectorClock, hashMapping: immutable.Seq[String]) = {
    VectorClock(version.versions.map({
      case msg.VectorClock.Version(h, t) ⇒ (VectorClock.Node.fromHash(hashMapping(h)), VectorClock.Timestamp(t))
    })(breakOut))
  }

  private def gossipEnvelopeFromProto(envelope: msg.GossipEnvelope): GossipEnvelope =
    GossipEnvelope(uniqueAddressFromProto(envelope.from), uniqueAddressFromProto(envelope.to), gossipFromProto(envelope.gossip))

  private def gossipStatusFromProto(status: msg.GossipStatus): GossipStatus =
    GossipStatus(uniqueAddressFromProto(status.from), vectorClockFromProto(status.version, status.allHashes))

  private def metricsGossipEnvelopeToProto(envelope: MetricsGossipEnvelope): msg.MetricsGossipEnvelope = {
    val mgossip = envelope.gossip
    val allAddresses = mgossip.nodes.foldLeft(Set.empty[Address])((s, n) ⇒ s + n.address).to[Vector]
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allMetricNames = mgossip.nodes.foldLeft(Set.empty[String])((s, n) ⇒ s ++ n.metrics.iterator.map(_.name)).to[Vector]
    val metricNamesMapping = allMetricNames.zipWithIndex.toMap

    def mapAddress(address: Address) = mapWithErrorMessage(addressMapping, address, "address")
    def mapName(name: String) = mapWithErrorMessage(metricNamesMapping, name, "address")

    def ewmaToProto(ewma: Option[EWMA]): Option[msg.NodeMetrics.EWMA] = ewma.map(x ⇒ msg.NodeMetrics.EWMA(x.value, x.alpha))

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
          msg.NodeMetrics.Number(msg.NodeMetrics.NumberType.Serialized, None, None, Some(ByteString.copyFrom(bos.toByteArray)))
      }
    }

    def metricToProto(metric: Metric): msg.NodeMetrics.Metric =
      msg.NodeMetrics.Metric(mapName(metric.name), numberToProto(metric.value), ewmaToProto(metric.average))

    def nodeMetricsToProto(nodeMetrics: NodeMetrics): msg.NodeMetrics =
      msg.NodeMetrics(mapAddress(nodeMetrics.address), nodeMetrics.timestamp, nodeMetrics.metrics.map(metricToProto)(breakOut))

    val nodeMetrics: Vector[msg.NodeMetrics] = mgossip.nodes.map(nodeMetricsToProto)(breakOut)

    msg.MetricsGossipEnvelope(addressToProto(envelope.from),
      msg.MetricsGossip(allAddresses.map(addressToProto), allMetricNames, nodeMetrics), envelope.reply)
  }

  private def metricsGossipEnvelopeFromBinary(bytes: Array[Byte]): MetricsGossipEnvelope =
    metricsGossipEnvelopeFromProto(msg.MetricsGossipEnvelope.defaultInstance.mergeFrom(decompress(bytes)))

  private def metricsGossipEnvelopeFromProto(envelope: msg.MetricsGossipEnvelope): MetricsGossipEnvelope = {
    val mgossip = envelope.gossip
    val addressMapping = mgossip.allAddresses.map(addressFromProto)
    val metricNameMapping = mgossip.allMetricNames

    def ewmaFromProto(ewma: Option[msg.NodeMetrics.EWMA]): Option[EWMA] = ewma.map(x ⇒ EWMA(x.value, x.alpha))

    def numberFromProto(number: msg.NodeMetrics.Number): Number = {
      import msg.NodeMetrics.Number
      import msg.NodeMetrics.NumberType
      number match {
        case Number(NumberType.Double, _, Some(n), _)  ⇒ jl.Double.longBitsToDouble(n)
        case Number(NumberType.Long, _, Some(n), _)    ⇒ n
        case Number(NumberType.Float, Some(n), _, _)   ⇒ jl.Float.intBitsToFloat(n)
        case Number(NumberType.Integer, Some(n), _, _) ⇒ n
        case Number(NumberType.Serialized, _, _, Some(b)) ⇒
          val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader, new ByteArrayInputStream(b.toByteArray))
          val obj = in.readObject
          in.close()
          obj.asInstanceOf[jl.Number]
      }
    }

    def metricFromProto(metric: msg.NodeMetrics.Metric): Metric =
      Metric(metricNameMapping(metric.nameIndex), numberFromProto(metric.number), ewmaFromProto(metric.ewma))

    def nodeMetricsFromProto(nodeMetrics: msg.NodeMetrics): NodeMetrics =
      NodeMetrics(addressMapping(nodeMetrics.addressIndex), nodeMetrics.timestamp, nodeMetrics.metrics.map(metricFromProto)(breakOut))

    val nodeMetrics: Set[NodeMetrics] = mgossip.nodeMetrics.map(nodeMetricsFromProto)(breakOut)

    MetricsGossipEnvelope(addressFromProto(envelope.from), MetricsGossip(nodeMetrics), envelope.reply)
  }

}
