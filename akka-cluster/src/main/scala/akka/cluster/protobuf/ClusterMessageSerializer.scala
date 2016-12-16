/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.protobuf

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectOutputStream }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }
import java.{ lang ⇒ jl }

import akka.actor.{ Address, ExtendedActorSystem }
import akka.cluster._
import akka.cluster.protobuf.msg.{ ClusterMessages ⇒ cm }
import akka.serialization.BaseSerializer
import akka.util.ClassLoaderObjectInputStream
import akka.protobuf.{ ByteString, MessageLite }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.duration.Deadline
import java.io.NotSerializableException

/**
 * Protobuf serializer of cluster messages.
 */
class ClusterMessageSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  @deprecated("Use constructor with ExtendedActorSystem", "2.4")
  def this() = this(null)

  // TODO remove this when deprecated this() is removed
  override val identifier: Int =
    if (system eq null) 5
    else identifierFromConfig

  private final val BufferSize = 1024 * 4
  // must be lazy because serializer is initialized from Cluster extension constructor
  private lazy val GossipTimeToLive = Cluster(system).settings.GossipTimeToLive

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ClusterMessage], Array[Byte] ⇒ AnyRef](
    classOf[InternalClusterAction.Join] → {
      case bytes ⇒
        val m = cm.Join.parseFrom(bytes)
        InternalClusterAction.Join(
          uniqueAddressFromProto(m.getNode),
          Set.empty[String] ++ m.getRolesList.asScala)
    },
    classOf[InternalClusterAction.Welcome] → {
      case bytes ⇒
        val m = cm.Welcome.parseFrom(decompress(bytes))
        InternalClusterAction.Welcome(uniqueAddressFromProto(m.getFrom), gossipFromProto(m.getGossip))
    },
    classOf[ClusterUserAction.Leave] → (bytes ⇒ ClusterUserAction.Leave(addressFromBinary(bytes))),
    classOf[ClusterUserAction.Down] → (bytes ⇒ ClusterUserAction.Down(addressFromBinary(bytes))),
    InternalClusterAction.InitJoin.getClass → (_ ⇒ InternalClusterAction.InitJoin),
    classOf[InternalClusterAction.InitJoinAck] → (bytes ⇒ InternalClusterAction.InitJoinAck(addressFromBinary(bytes))),
    classOf[InternalClusterAction.InitJoinNack] → (bytes ⇒ InternalClusterAction.InitJoinNack(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatSender.Heartbeat] → (bytes ⇒ ClusterHeartbeatSender.Heartbeat(addressFromBinary(bytes))),
    classOf[ClusterHeartbeatSender.HeartbeatRsp] → (bytes ⇒ ClusterHeartbeatSender.HeartbeatRsp(uniqueAddressFromBinary(bytes))),
    classOf[GossipStatus] → gossipStatusFromBinary,
    classOf[GossipEnvelope] → gossipEnvelopeFromBinary,
    classOf[MetricsGossipEnvelope] → metricsGossipEnvelopeFromBinary)

  def includeManifest: Boolean = true

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case ClusterHeartbeatSender.Heartbeat(from)      ⇒ addressToProtoByteArray(from)
    case ClusterHeartbeatSender.HeartbeatRsp(from)   ⇒ uniqueAddressToProtoByteArray(from)
    case m: GossipEnvelope                           ⇒ gossipEnvelopeToProto(m).toByteArray
    case m: GossipStatus                             ⇒ gossipStatusToProto(m).toByteArray
    case m: MetricsGossipEnvelope                    ⇒ compress(metricsGossipEnvelopeToProto(m))
    case InternalClusterAction.Join(node, roles)     ⇒ joinToProto(node, roles).toByteArray
    case InternalClusterAction.Welcome(from, gossip) ⇒ compress(welcomeToProto(from, gossip))
    case ClusterUserAction.Leave(address)            ⇒ addressToProtoByteArray(address)
    case ClusterUserAction.Down(address)             ⇒ addressToProtoByteArray(address)
    case InternalClusterAction.InitJoin              ⇒ cm.Empty.getDefaultInstance.toByteArray
    case InternalClusterAction.InitJoinAck(address)  ⇒ addressToProtoByteArray(address)
    case InternalClusterAction.InitJoinNack(address) ⇒ addressToProtoByteArray(address)
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
  }

  def compress(msg: MessageLite): Array[Byte] = {
    val bos = new ByteArrayOutputStream(BufferSize)
    val zip = new GZIPOutputStream(bos)
    try msg.writeTo(zip)
    finally zip.close()
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

    try readChunk()
    finally in.close()
    out.toByteArray
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = clazz match {
    case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ClusterMessage]]) match {
      case Some(f) ⇒ f(bytes)
      case None    ⇒ throw new NotSerializableException(s"Unimplemented deserialization of message class $c in ClusterSerializer")
    }
    case _ ⇒ throw new IllegalArgumentException("Need a cluster message class to be able to deserialize bytes in ClusterSerializer")
  }

  private def addressFromBinary(bytes: Array[Byte]): Address =
    addressFromProto(cm.Address.parseFrom(bytes))

  private def uniqueAddressFromBinary(bytes: Array[Byte]): UniqueAddress =
    uniqueAddressFromProto(cm.UniqueAddress.parseFrom(bytes))

  private def addressToProto(address: Address): cm.Address.Builder = address match {
    case Address(protocol, actorSystem, Some(host), Some(port)) ⇒
      cm.Address.newBuilder().setSystem(actorSystem).setHostname(host).setPort(port).setProtocol(protocol)
    case _ ⇒ throw new IllegalArgumentException(s"Address [$address] could not be serialized: host or port missing.")
  }

  private def addressToProtoByteArray(address: Address): Array[Byte] = addressToProto(address).build.toByteArray

  private def uniqueAddressToProto(uniqueAddress: UniqueAddress): cm.UniqueAddress.Builder = {
    cm.UniqueAddress.newBuilder()
      .setAddress(addressToProto(uniqueAddress.address))
      .setUid(uniqueAddress.longUid.toInt)
      .setUid2((uniqueAddress.longUid >> 32).toInt)
  }

  private def uniqueAddressToProtoByteArray(uniqueAddress: UniqueAddress): Array[Byte] =
    uniqueAddressToProto(uniqueAddress).build.toByteArray

  // we don't care about races here since it's just a cache
  @volatile
  private var protocolCache: String = null
  @volatile
  private var systemCache: String = null

  private def getProtocol(address: cm.Address): String = {
    val p = address.getProtocol
    val pc = protocolCache
    if (pc == p) pc
    else {
      protocolCache = p
      p
    }
  }

  private def getSystem(address: cm.Address): String = {
    val s = address.getSystem
    val sc = systemCache
    if (sc == s) sc
    else {
      systemCache = s
      s
    }
  }

  private def addressFromProto(address: cm.Address): Address =
    Address(getProtocol(address), getSystem(address), address.getHostname, address.getPort)

  private def uniqueAddressFromProto(uniqueAddress: cm.UniqueAddress): UniqueAddress = {

    UniqueAddress(
      addressFromProto(uniqueAddress.getAddress),
      if (uniqueAddress.hasUid2) {
        // new remote node join the two parts of the long uid back
        (uniqueAddress.getUid2.toLong << 32) | (uniqueAddress.getUid & 0xFFFFFFFFL)
      } else {
        // old remote node
        uniqueAddress.getUid.toLong
      })
  }

  private val memberStatusToInt = scala.collection.immutable.HashMap[MemberStatus, Int](
    MemberStatus.Joining → cm.MemberStatus.Joining_VALUE,
    MemberStatus.Up → cm.MemberStatus.Up_VALUE,
    MemberStatus.Leaving → cm.MemberStatus.Leaving_VALUE,
    MemberStatus.Exiting → cm.MemberStatus.Exiting_VALUE,
    MemberStatus.Down → cm.MemberStatus.Down_VALUE,
    MemberStatus.Removed → cm.MemberStatus.Removed_VALUE,
    MemberStatus.WeaklyUp → cm.MemberStatus.WeaklyUp_VALUE)

  private val memberStatusFromInt = memberStatusToInt.map { case (a, b) ⇒ (b, a) }

  private val reachabilityStatusToInt = scala.collection.immutable.HashMap[Reachability.ReachabilityStatus, Int](
    Reachability.Reachable → cm.ReachabilityStatus.Reachable_VALUE,
    Reachability.Unreachable → cm.ReachabilityStatus.Unreachable_VALUE,
    Reachability.Terminated → cm.ReachabilityStatus.Terminated_VALUE)

  private val reachabilityStatusFromInt = reachabilityStatusToInt.map { case (a, b) ⇒ (b, a) }

  private def mapWithErrorMessage[T](map: Map[T, Int], value: T, unknown: String): Int = map.get(value) match {
    case Some(x) ⇒ x
    case _       ⇒ throw new IllegalArgumentException(s"Unknown $unknown [$value] in cluster message")
  }

  private def joinToProto(node: UniqueAddress, roles: Set[String]): cm.Join =
    cm.Join.newBuilder().setNode(uniqueAddressToProto(node)).addAllRoles(roles.asJava).build()

  private def welcomeToProto(from: UniqueAddress, gossip: Gossip): cm.Welcome =
    cm.Welcome.newBuilder().setFrom(uniqueAddressToProto(from)).setGossip(gossipToProto(gossip)).build()

  private def gossipToProto(gossip: Gossip): cm.Gossip.Builder = {
    val allMembers = gossip.members.toVector
    val allAddresses: Vector[UniqueAddress] = allMembers.map(_.uniqueAddress)
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allRoles = allMembers.foldLeft(Set.empty[String])((acc, m) ⇒ acc union m.roles).to[Vector]
    val roleMapping = allRoles.zipWithIndex.toMap
    val allHashes = gossip.version.versions.keys.to[Vector]
    val hashMapping = allHashes.zipWithIndex.toMap

    def mapUniqueAddress(uniqueAddress: UniqueAddress): Integer = mapWithErrorMessage(addressMapping, uniqueAddress, "address")
    def mapRole(role: String): Integer = mapWithErrorMessage(roleMapping, role, "role")

    def memberToProto(member: Member) =
      cm.Member.newBuilder.setAddressIndex(mapUniqueAddress(member.uniqueAddress)).setUpNumber(member.upNumber).
        setStatus(cm.MemberStatus.valueOf(memberStatusToInt(member.status))).
        addAllRolesIndexes(member.roles.map(mapRole).asJava)

    def reachabilityToProto(reachability: Reachability): Iterable[cm.ObserverReachability.Builder] = {
      reachability.versions.map {
        case (observer, version) ⇒
          val subjectReachability = reachability.recordsFrom(observer).map(r ⇒
            cm.SubjectReachability.newBuilder().setAddressIndex(mapUniqueAddress(r.subject)).
              setStatus(cm.ReachabilityStatus.valueOf(reachabilityStatusToInt(r.status))).
              setVersion(r.version))
          cm.ObserverReachability.newBuilder().setAddressIndex(mapUniqueAddress(observer)).setVersion(version).
            addAllSubjectReachability(subjectReachability.map(_.build).asJava)
      }
    }

    val reachability = reachabilityToProto(gossip.overview.reachability)
    val members = gossip.members.map(memberToProto)
    val seen = gossip.overview.seen.map(mapUniqueAddress)

    val overview = cm.GossipOverview.newBuilder.addAllSeen(seen.asJava).
      addAllObserverReachability(reachability.map(_.build).asJava)

    cm.Gossip.newBuilder().addAllAllAddresses(allAddresses.map(uniqueAddressToProto(_).build).asJava).
      addAllAllRoles(allRoles.asJava).addAllAllHashes(allHashes.asJava).addAllMembers(members.map(_.build).asJava).
      setOverview(overview).setVersion(vectorClockToProto(gossip.version, hashMapping))
  }

  private def vectorClockToProto(version: VectorClock, hashMapping: Map[String, Int]): cm.VectorClock.Builder = {
    val versions: Iterable[cm.VectorClock.Version.Builder] = version.versions.map {
      case (n, t) ⇒ cm.VectorClock.Version.newBuilder().setHashIndex(mapWithErrorMessage(hashMapping, n, "hash")).
        setTimestamp(t)
    }
    cm.VectorClock.newBuilder().setTimestamp(0).addAllVersions(versions.map(_.build).asJava)
  }

  private def gossipEnvelopeToProto(envelope: GossipEnvelope): cm.GossipEnvelope =
    cm.GossipEnvelope.newBuilder().
      setFrom(uniqueAddressToProto(envelope.from)).
      setTo(uniqueAddressToProto(envelope.to)).
      setSerializedGossip(ByteString.copyFrom(compress(gossipToProto(envelope.gossip).build))).
      build

  private def gossipStatusToProto(status: GossipStatus): cm.GossipStatus = {
    val allHashes = status.version.versions.keys.toVector
    val hashMapping = allHashes.zipWithIndex.toMap
    cm.GossipStatus.newBuilder().setFrom(uniqueAddressToProto(status.from)).addAllAllHashes(allHashes.asJava).
      setVersion(vectorClockToProto(status.version, hashMapping)).build()
  }

  private def gossipEnvelopeFromBinary(bytes: Array[Byte]): GossipEnvelope =
    gossipEnvelopeFromProto(cm.GossipEnvelope.parseFrom(bytes))

  private def gossipStatusFromBinary(bytes: Array[Byte]): GossipStatus =
    gossipStatusFromProto(cm.GossipStatus.parseFrom(bytes))

  private def gossipFromProto(gossip: cm.Gossip): Gossip = {
    import scala.collection.breakOut
    val addressMapping: Vector[UniqueAddress] =
      gossip.getAllAddressesList.asScala.map(uniqueAddressFromProto)(breakOut)
    val roleMapping: Vector[String] = gossip.getAllRolesList.asScala.map(identity)(breakOut)
    val hashMapping: Vector[String] = gossip.getAllHashesList.asScala.map(identity)(breakOut)

    def reachabilityFromProto(observerReachability: Iterable[cm.ObserverReachability]): Reachability = {
      val recordBuilder = new immutable.VectorBuilder[Reachability.Record]
      val versionsBuilder = new scala.collection.mutable.MapBuilder[UniqueAddress, Long, Map[UniqueAddress, Long]](Map.empty)
      for (o ← observerReachability) {
        val observer = addressMapping(o.getAddressIndex)
        versionsBuilder += ((observer, o.getVersion))
        for (s ← o.getSubjectReachabilityList.asScala) {
          val subject = addressMapping(s.getAddressIndex)
          val record = Reachability.Record(observer, subject, reachabilityStatusFromInt(s.getStatus.getNumber), s.getVersion)
          recordBuilder += record
        }
      }

      Reachability.create(recordBuilder.result(), versionsBuilder.result())
    }

    def memberFromProto(member: cm.Member) =
      new Member(addressMapping(member.getAddressIndex), member.getUpNumber, memberStatusFromInt(member.getStatus.getNumber),
        member.getRolesIndexesList.asScala.map(roleMapping(_))(breakOut))

    val members: immutable.SortedSet[Member] = gossip.getMembersList.asScala.map(memberFromProto)(breakOut)

    val reachability = reachabilityFromProto(gossip.getOverview.getObserverReachabilityList.asScala)
    val seen: Set[UniqueAddress] = gossip.getOverview.getSeenList.asScala.map(addressMapping(_))(breakOut)
    val overview = GossipOverview(seen, reachability)

    Gossip(members, overview, vectorClockFromProto(gossip.getVersion, hashMapping))
  }

  private def vectorClockFromProto(version: cm.VectorClock, hashMapping: immutable.Seq[String]) = {
    import scala.collection.breakOut
    VectorClock(version.getVersionsList.asScala.map(
      v ⇒ (VectorClock.Node.fromHash(hashMapping(v.getHashIndex)), v.getTimestamp))(breakOut))
  }

  private def gossipEnvelopeFromProto(envelope: cm.GossipEnvelope): GossipEnvelope = {
    val serializedGossip = envelope.getSerializedGossip
    GossipEnvelope(uniqueAddressFromProto(envelope.getFrom), uniqueAddressFromProto(envelope.getTo),
      Deadline.now + GossipTimeToLive, () ⇒ gossipFromProto(cm.Gossip.parseFrom(decompress(serializedGossip.toByteArray))))
  }

  private def gossipStatusFromProto(status: cm.GossipStatus): GossipStatus =
    GossipStatus(uniqueAddressFromProto(status.getFrom), vectorClockFromProto(
      status.getVersion,
      status.getAllHashesList.asScala.toVector))

  private def metricsGossipEnvelopeToProto(envelope: MetricsGossipEnvelope): cm.MetricsGossipEnvelope = {
    import scala.collection.breakOut
    val allNodeMetrics = envelope.gossip.nodes
    val allAddresses: Vector[Address] = allNodeMetrics.map(_.address)(breakOut)
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allMetricNames: Vector[String] = allNodeMetrics.foldLeft(Set.empty[String])((s, n) ⇒ s ++ n.metrics.iterator.map(_.name)).toVector
    val metricNamesMapping = allMetricNames.zipWithIndex.toMap
    def mapAddress(address: Address) = mapWithErrorMessage(addressMapping, address, "address")
    def mapName(name: String) = mapWithErrorMessage(metricNamesMapping, name, "address")

    def ewmaToProto(ewma: Option[EWMA]): Option[cm.NodeMetrics.EWMA.Builder] = ewma.map {
      x ⇒ cm.NodeMetrics.EWMA.newBuilder().setValue(x.value).setAlpha(x.alpha)
    }

    def numberToProto(number: Number): cm.NodeMetrics.Number.Builder = {
      import cm.NodeMetrics.Number
      import cm.NodeMetrics.NumberType
      number match {
        case n: jl.Double  ⇒ Number.newBuilder().setType(NumberType.Double).setValue64(jl.Double.doubleToLongBits(n))
        case n: jl.Long    ⇒ Number.newBuilder().setType(NumberType.Long).setValue64(n)
        case n: jl.Float   ⇒ Number.newBuilder().setType(NumberType.Float).setValue32(jl.Float.floatToIntBits(n))
        case n: jl.Integer ⇒ Number.newBuilder().setType(NumberType.Integer).setValue32(n)
        case _ ⇒
          val bos = new ByteArrayOutputStream
          val out = new ObjectOutputStream(bos)
          out.writeObject(number)
          out.close()
          Number.newBuilder().setType(NumberType.Serialized).setSerialized(ByteString.copyFrom(bos.toByteArray))
      }
    }

    def metricToProto(metric: Metric): cm.NodeMetrics.Metric.Builder = {
      val builder = cm.NodeMetrics.Metric.newBuilder().setNameIndex(mapName(metric.name)).setNumber(numberToProto(metric.value))
      ewmaToProto(metric.average).map(builder.setEwma).getOrElse(builder)
    }

    def nodeMetricsToProto(nodeMetrics: NodeMetrics): cm.NodeMetrics.Builder =
      cm.NodeMetrics.newBuilder().setAddressIndex(mapAddress(nodeMetrics.address)).setTimestamp(nodeMetrics.timestamp).
        addAllMetrics(nodeMetrics.metrics.map(metricToProto(_).build).asJava)

    val nodeMetrics: Iterable[cm.NodeMetrics] = allNodeMetrics.map(nodeMetricsToProto(_).build)

    cm.MetricsGossipEnvelope.newBuilder().setFrom(addressToProto(envelope.from)).setGossip(
      cm.MetricsGossip.newBuilder().addAllAllAddresses(allAddresses.map(addressToProto(_).build()).asJava).
        addAllAllMetricNames(allMetricNames.asJava).addAllNodeMetrics(nodeMetrics.asJava)).
      setReply(envelope.reply).build
  }

  private def metricsGossipEnvelopeFromBinary(bytes: Array[Byte]): MetricsGossipEnvelope =
    metricsGossipEnvelopeFromProto(cm.MetricsGossipEnvelope.parseFrom(decompress(bytes)))

  private def metricsGossipEnvelopeFromProto(envelope: cm.MetricsGossipEnvelope): MetricsGossipEnvelope = {
    import scala.collection.breakOut
    val mgossip = envelope.getGossip
    val addressMapping: Vector[Address] = mgossip.getAllAddressesList.asScala.map(addressFromProto)(breakOut)
    val metricNameMapping: Vector[String] = mgossip.getAllMetricNamesList.asScala.toVector

    def ewmaFromProto(ewma: cm.NodeMetrics.EWMA): Option[EWMA] =
      Some(EWMA(ewma.getValue, ewma.getAlpha))

    def numberFromProto(number: cm.NodeMetrics.Number): Number = {
      import cm.NodeMetrics.NumberType
      number.getType.getNumber match {
        case NumberType.Double_VALUE  ⇒ jl.Double.longBitsToDouble(number.getValue64)
        case NumberType.Long_VALUE    ⇒ number.getValue64
        case NumberType.Float_VALUE   ⇒ jl.Float.intBitsToFloat(number.getValue32)
        case NumberType.Integer_VALUE ⇒ number.getValue32
        case NumberType.Serialized_VALUE ⇒
          val in = new ClassLoaderObjectInputStream(
            system.dynamicAccess.classLoader,
            new ByteArrayInputStream(number.getSerialized.toByteArray))
          val obj = in.readObject
          in.close()
          obj.asInstanceOf[jl.Number]
      }
    }

    def metricFromProto(metric: cm.NodeMetrics.Metric): Metric =
      Metric(metricNameMapping(metric.getNameIndex), numberFromProto(metric.getNumber),
        if (metric.hasEwma) ewmaFromProto(metric.getEwma) else None)

    def nodeMetricsFromProto(nodeMetrics: cm.NodeMetrics): NodeMetrics =
      NodeMetrics(addressMapping(nodeMetrics.getAddressIndex), nodeMetrics.getTimestamp,
        nodeMetrics.getMetricsList.asScala.map(metricFromProto)(breakOut))

    val nodeMetrics: Set[NodeMetrics] = mgossip.getNodeMetricsList.asScala.map(nodeMetricsFromProto)(breakOut)

    MetricsGossipEnvelope(addressFromProto(envelope.getFrom), MetricsGossip(nodeMetrics), envelope.getReply)
  }

}
