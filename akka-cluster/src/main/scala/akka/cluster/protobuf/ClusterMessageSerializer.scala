/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.protobuf

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }

import akka.actor.{ Address, ExtendedActorSystem }
import akka.cluster._
import akka.cluster.protobuf.msg.{ ClusterMessages => cm }
import akka.serialization._
import akka.protobuf.{ ByteString, MessageLite }
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.concurrent.duration.Deadline

import akka.annotation.InternalApi
import akka.cluster.InternalClusterAction._
import akka.cluster.routing.{ ClusterRouterPool, ClusterRouterPoolSettings }
import akka.routing.Pool
import akka.util.ccompat._
import akka.util.ccompat.imm._
import com.typesafe.config.{ Config, ConfigFactory, ConfigRenderOptions }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ClusterMessageSerializer {
  // FIXME use short manifests when we can break wire compatibility
  // needs to be full class names for backwards compatibility
  val JoinManifest = s"akka.cluster.InternalClusterAction$$Join"
  val WelcomeManifest = s"akka.cluster.InternalClusterAction$$Welcome"
  val LeaveManifest = s"akka.cluster.ClusterUserAction$$Leave"
  val DownManifest = s"akka.cluster.ClusterUserAction$$Down"
  // #24622 wire compatibility
  // we need to use this object name rather than classname to be able to join a 2.5.9 cluster during rolling upgrades
  val InitJoinManifest = s"akka.cluster.InternalClusterAction$$InitJoin$$"
  val InitJoinAckManifest = s"akka.cluster.InternalClusterAction$$InitJoinAck"
  val InitJoinNackManifest = s"akka.cluster.InternalClusterAction$$InitJoinNack"
  val HeartBeatManifest = s"akka.cluster.ClusterHeartbeatSender$$Heartbeat"
  val HeartBeatRspManifest = s"akka.cluster.ClusterHeartbeatSender$$HeartbeatRsp"
  val ExitingConfirmedManifest = s"akka.cluster.InternalClusterAction$$ExitingConfirmed"
  val GossipStatusManifest = "akka.cluster.GossipStatus"
  val GossipEnvelopeManifest = "akka.cluster.GossipEnvelope"
  val ClusterRouterPoolManifest = "akka.cluster.routing.ClusterRouterPool"

  private final val BufferSize = 1024 * 4
}

/**
 * Protobuf serializer of cluster messages.
 */
final class ClusterMessageSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {
  import ClusterMessageSerializer._
  private lazy val serialization = SerializationExtension(system)

  // must be lazy because serializer is initialized from Cluster extension constructor
  private lazy val GossipTimeToLive = Cluster(system).settings.GossipTimeToLive

  def manifest(o: AnyRef): String = o match {
    case _: InternalClusterAction.Join          => JoinManifest
    case _: InternalClusterAction.Welcome       => WelcomeManifest
    case _: ClusterUserAction.Leave             => LeaveManifest
    case _: ClusterUserAction.Down              => DownManifest
    case _: InternalClusterAction.InitJoin      => InitJoinManifest
    case _: InternalClusterAction.InitJoinAck   => InitJoinAckManifest
    case _: InternalClusterAction.InitJoinNack  => InitJoinNackManifest
    case _: ClusterHeartbeatSender.Heartbeat    => HeartBeatManifest
    case _: ClusterHeartbeatSender.HeartbeatRsp => HeartBeatRspManifest
    case _: ExitingConfirmed                    => ExitingConfirmedManifest
    case _: GossipStatus                        => GossipStatusManifest
    case _: GossipEnvelope                      => GossipEnvelopeManifest
    case _: ClusterRouterPool                   => ClusterRouterPoolManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case ClusterHeartbeatSender.Heartbeat(from)                  => addressToProtoByteArray(from)
    case ClusterHeartbeatSender.HeartbeatRsp(from)               => uniqueAddressToProtoByteArray(from)
    case m: GossipEnvelope                                       => gossipEnvelopeToProto(m).toByteArray
    case m: GossipStatus                                         => gossipStatusToProto(m).toByteArray
    case InternalClusterAction.Join(node, roles)                 => joinToProto(node, roles).toByteArray
    case InternalClusterAction.Welcome(from, gossip)             => compress(welcomeToProto(from, gossip))
    case ClusterUserAction.Leave(address)                        => addressToProtoByteArray(address)
    case ClusterUserAction.Down(address)                         => addressToProtoByteArray(address)
    case InternalClusterAction.InitJoin(config)                  => initJoinToProto(config).toByteArray
    case InternalClusterAction.InitJoinAck(address, configCheck) => initJoinAckToByteArray(address, configCheck)
    case InternalClusterAction.InitJoinNack(address)             => addressToProtoByteArray(address)
    case InternalClusterAction.ExitingConfirmed(node)            => uniqueAddressToProtoByteArray(node)
    case rp: ClusterRouterPool                                   => clusterRouterPoolToProtoByteArray(rp)
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case HeartBeatManifest         => deserializeHeartBeat(bytes)
    case HeartBeatRspManifest      => deserializeHeartBeatRsp(bytes)
    case GossipStatusManifest      => deserializeGossipStatus(bytes)
    case GossipEnvelopeManifest    => deserializeGossipEnvelope(bytes)
    case InitJoinManifest          => deserializeInitJoin(bytes)
    case InitJoinAckManifest       => deserializeInitJoinAck(bytes)
    case InitJoinNackManifest      => deserializeInitJoinNack(bytes)
    case JoinManifest              => deserializeJoin(bytes)
    case WelcomeManifest           => deserializeWelcome(bytes)
    case LeaveManifest             => deserializeLeave(bytes)
    case DownManifest              => deserializeDown(bytes)
    case ExitingConfirmedManifest  => deserializeExitingConfirmed(bytes)
    case ClusterRouterPoolManifest => deserializeClusterRouterPool(bytes)
    case _                         => throw new IllegalArgumentException(s"Unknown manifest [${manifest}]")
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
      case -1 => ()
      case n =>
        out.write(buffer, 0, n)
        readChunk()
    }

    try readChunk()
    finally in.close()
    out.toByteArray
  }

  private def addressFromBinary(bytes: Array[Byte]): Address =
    addressFromProto(cm.Address.parseFrom(bytes))

  private def uniqueAddressFromBinary(bytes: Array[Byte]): UniqueAddress =
    uniqueAddressFromProto(cm.UniqueAddress.parseFrom(bytes))

  private[akka] def addressToProto(address: Address): cm.Address.Builder = address match {
    case Address(protocol, actorSystem, Some(host), Some(port)) =>
      cm.Address.newBuilder().setSystem(actorSystem).setHostname(host).setPort(port).setProtocol(protocol)
    case _ => throw new IllegalArgumentException(s"Address [$address] could not be serialized: host or port missing.")
  }

  private def addressToProtoByteArray(address: Address): Array[Byte] = addressToProto(address).build.toByteArray

  private def uniqueAddressToProto(uniqueAddress: UniqueAddress): cm.UniqueAddress.Builder = {
    cm.UniqueAddress
      .newBuilder()
      .setAddress(addressToProto(uniqueAddress.address))
      .setUid(uniqueAddress.longUid.toInt)
      .setUid2((uniqueAddress.longUid >> 32).toInt)
  }

  private def uniqueAddressToProtoByteArray(uniqueAddress: UniqueAddress): Array[Byte] =
    uniqueAddressToProto(uniqueAddress).build.toByteArray

  private def clusterRouterPoolToProtoByteArray(rp: ClusterRouterPool): Array[Byte] = {
    val builder = cm.ClusterRouterPool.newBuilder()
    builder.setPool(poolToProto(rp.local))
    builder.setSettings(clusterRouterPoolSettingsToProto(rp.settings))
    builder.build().toByteArray
  }

  private def poolToProto(pool: Pool): cm.Pool = {
    val builder = cm.Pool.newBuilder()
    val serializer = serialization.findSerializerFor(pool)
    builder.setSerializerId(serializer.identifier).setData(ByteString.copyFrom(serializer.toBinary(pool)))
    val manifest = Serializers.manifestFor(serializer, pool)
    builder.setManifest(manifest)
    builder.build()
  }

  private def clusterRouterPoolSettingsToProto(settings: ClusterRouterPoolSettings): cm.ClusterRouterPoolSettings = {
    val builder = cm.ClusterRouterPoolSettings.newBuilder()
    builder
      .setAllowLocalRoutees(settings.allowLocalRoutees)
      .setMaxInstancesPerNode(settings.maxInstancesPerNode)
      .setTotalInstances(settings.totalInstances)
      .addAllUseRoles(settings.useRoles.asJava)

    // for backwards compatibility
    settings.useRole.foreach(builder.setUseRole)

    builder.build()
  }

  // we don't care about races here since it's just a cache
  @volatile
  private var protocolCache: String = _
  @volatile
  private var systemCache: String = _

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

  private def deserializeJoin(bytes: Array[Byte]): InternalClusterAction.Join = {
    val m = cm.Join.parseFrom(bytes)
    val roles = Set.empty[String] ++ m.getRolesList.asScala
    InternalClusterAction.Join(
      uniqueAddressFromProto(m.getNode),
      if (roles.exists(_.startsWith(ClusterSettings.DcRolePrefix))) roles
      else roles + (ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter))
  }

  private def deserializeWelcome(bytes: Array[Byte]): InternalClusterAction.Welcome = {
    val m = cm.Welcome.parseFrom(decompress(bytes))
    InternalClusterAction.Welcome(uniqueAddressFromProto(m.getFrom), gossipFromProto(m.getGossip))
  }

  private def deserializeLeave(bytes: Array[Byte]): ClusterUserAction.Leave = {
    ClusterUserAction.Leave(addressFromBinary(bytes))
  }

  private def deserializeDown(bytes: Array[Byte]): ClusterUserAction.Down = {
    ClusterUserAction.Down(addressFromBinary(bytes))
  }

  private def deserializeInitJoin(bytes: Array[Byte]): InternalClusterAction.InitJoin = {
    val m = cm.InitJoin.parseFrom(bytes)
    if (m.hasCurrentConfig)
      InternalClusterAction.InitJoin(ConfigFactory.parseString(m.getCurrentConfig))
    else
      InternalClusterAction.InitJoin(ConfigFactory.empty)
  }

  private def deserializeInitJoinAck(bytes: Array[Byte]): InternalClusterAction.InitJoinAck = {
    try {
      val i = cm.InitJoinAck.parseFrom(bytes)
      val configCheck =
        i.getConfigCheck.getType match {
          case cm.ConfigCheck.Type.CompatibleConfig =>
            CompatibleConfig(ConfigFactory.parseString(i.getConfigCheck.getClusterConfig))
          case cm.ConfigCheck.Type.IncompatibleConfig => IncompatibleConfig
          case cm.ConfigCheck.Type.UncheckedConfig    => UncheckedConfig
        }

      InternalClusterAction.InitJoinAck(addressFromProto(i.getAddress), configCheck)
    } catch {
      case _: akka.protobuf.InvalidProtocolBufferException =>
        // nodes previous to 2.5.9 sends just an address
        InternalClusterAction.InitJoinAck(addressFromBinary(bytes), UncheckedConfig)
    }
  }

  private def deserializeExitingConfirmed(bytes: Array[Byte]): InternalClusterAction.ExitingConfirmed = {
    InternalClusterAction.ExitingConfirmed(uniqueAddressFromBinary(bytes))
  }

  private def deserializeHeartBeatRsp(bytes: Array[Byte]): ClusterHeartbeatSender.HeartbeatRsp = {
    ClusterHeartbeatSender.HeartbeatRsp(uniqueAddressFromBinary(bytes))
  }

  private def deserializeHeartBeat(bytes: Array[Byte]): ClusterHeartbeatSender.Heartbeat = {
    ClusterHeartbeatSender.Heartbeat(addressFromBinary(bytes))
  }

  private def deserializeInitJoinNack(bytes: Array[Byte]): InternalClusterAction.InitJoinNack = {
    InternalClusterAction.InitJoinNack(addressFromBinary(bytes))
  }

  private def addressFromProto(address: cm.Address): Address =
    Address(getProtocol(address), getSystem(address), address.getHostname, address.getPort)

  private def uniqueAddressFromProto(uniqueAddress: cm.UniqueAddress): UniqueAddress = {

    UniqueAddress(addressFromProto(uniqueAddress.getAddress), if (uniqueAddress.hasUid2) {
      // new remote node join the two parts of the long uid back
      (uniqueAddress.getUid2.toLong << 32) | (uniqueAddress.getUid & 0XFFFFFFFFL)
    } else {
      // old remote node
      uniqueAddress.getUid.toLong
    })
  }

  private val memberStatusToInt = scala.collection.immutable.HashMap[MemberStatus, Int](
    MemberStatus.Joining -> cm.MemberStatus.Joining_VALUE,
    MemberStatus.Up -> cm.MemberStatus.Up_VALUE,
    MemberStatus.Leaving -> cm.MemberStatus.Leaving_VALUE,
    MemberStatus.Exiting -> cm.MemberStatus.Exiting_VALUE,
    MemberStatus.Down -> cm.MemberStatus.Down_VALUE,
    MemberStatus.Removed -> cm.MemberStatus.Removed_VALUE,
    MemberStatus.WeaklyUp -> cm.MemberStatus.WeaklyUp_VALUE)

  private val memberStatusFromInt = memberStatusToInt.map { case (a, b) => (b, a) }

  private val reachabilityStatusToInt = scala.collection.immutable.HashMap[Reachability.ReachabilityStatus, Int](
    Reachability.Reachable -> cm.ReachabilityStatus.Reachable_VALUE,
    Reachability.Unreachable -> cm.ReachabilityStatus.Unreachable_VALUE,
    Reachability.Terminated -> cm.ReachabilityStatus.Terminated_VALUE)

  private val reachabilityStatusFromInt = reachabilityStatusToInt.map { case (a, b) => (b, a) }

  private def mapWithErrorMessage[T](map: Map[T, Int], value: T, unknown: String): Int = map.get(value) match {
    case Some(x) => x
    case _       => throw new IllegalArgumentException(s"Unknown $unknown [$value] in cluster message")
  }

  private def joinToProto(node: UniqueAddress, roles: Set[String]): cm.Join =
    cm.Join.newBuilder().setNode(uniqueAddressToProto(node)).addAllRoles(roles.asJava).build()

  private def initJoinToProto(currentConfig: Config): cm.InitJoin = {
    cm.InitJoin.newBuilder().setCurrentConfig(currentConfig.root.render(ConfigRenderOptions.concise)).build()
  }

  private def initJoinAckToByteArray(address: Address, configCheck: ConfigCheck): Array[Byte] = {
    if (configCheck == ConfigCheckUnsupportedByJoiningNode)
      addressToProtoByteArray(address) // plain Address in 2.5.9 or earlier
    else
      initJoinAckToProto(address, configCheck).toByteArray
  }

  private def initJoinAckToProto(address: Address, configCheck: ConfigCheck): cm.InitJoinAck = {

    val configCheckBuilder = cm.ConfigCheck.newBuilder()
    configCheck match {
      case UncheckedConfig =>
        configCheckBuilder.setType(cm.ConfigCheck.Type.UncheckedConfig)

      case IncompatibleConfig =>
        configCheckBuilder.setType(cm.ConfigCheck.Type.IncompatibleConfig)

      case CompatibleConfig(conf) =>
        configCheckBuilder
          .setType(cm.ConfigCheck.Type.CompatibleConfig)
          .setClusterConfig(conf.root.render(ConfigRenderOptions.concise))

      case ConfigCheckUnsupportedByJoiningNode =>
        // handled as Address in initJoinAckToByteArray
        throw new IllegalStateException("Unexpected ConfigCheckUnsupportedByJoiningNode")
    }

    cm.InitJoinAck.newBuilder().setAddress(addressToProto(address)).setConfigCheck(configCheckBuilder.build()).build()
  }

  private def welcomeToProto(from: UniqueAddress, gossip: Gossip): cm.Welcome =
    cm.Welcome.newBuilder().setFrom(uniqueAddressToProto(from)).setGossip(gossipToProto(gossip)).build()

  private def gossipToProto(gossip: Gossip): cm.Gossip.Builder = {
    val allMembers = gossip.members.toVector
    val allAddresses: Vector[UniqueAddress] = allMembers.map(_.uniqueAddress) ++ gossip.tombstones.keys
    val addressMapping = allAddresses.zipWithIndex.toMap
    val allRoles = allMembers.foldLeft(Set.empty[String])((acc, m) => acc.union(m.roles)).to(Vector)
    val roleMapping = allRoles.zipWithIndex.toMap
    val allHashes = gossip.version.versions.keys.to(Vector)
    val hashMapping = allHashes.zipWithIndex.toMap

    def mapUniqueAddress(uniqueAddress: UniqueAddress): Integer =
      mapWithErrorMessage(addressMapping, uniqueAddress, "address")

    def mapRole(role: String): Integer = mapWithErrorMessage(roleMapping, role, "role")

    def memberToProto(member: Member) =
      cm.Member.newBuilder
        .setAddressIndex(mapUniqueAddress(member.uniqueAddress))
        .setUpNumber(member.upNumber)
        .setStatus(cm.MemberStatus.valueOf(memberStatusToInt(member.status)))
        .addAllRolesIndexes(member.roles.map(mapRole).asJava)

    def reachabilityToProto(reachability: Reachability): Iterable[cm.ObserverReachability.Builder] = {
      reachability.versions.map {
        case (observer, version) =>
          val subjectReachability = reachability
            .recordsFrom(observer)
            .map(
              r =>
                cm.SubjectReachability
                  .newBuilder()
                  .setAddressIndex(mapUniqueAddress(r.subject))
                  .setStatus(cm.ReachabilityStatus.valueOf(reachabilityStatusToInt(r.status)))
                  .setVersion(r.version))
          cm.ObserverReachability
            .newBuilder()
            .setAddressIndex(mapUniqueAddress(observer))
            .setVersion(version)
            .addAllSubjectReachability(subjectReachability.map(_.build).asJava)
      }
    }

    def tombstoneToProto(t: (UniqueAddress, Long)): cm.Tombstone =
      cm.Tombstone.newBuilder().setAddressIndex(mapUniqueAddress(t._1)).setTimestamp(t._2).build()

    val reachability = reachabilityToProto(gossip.overview.reachability)
    val members = gossip.members.unsorted.map(memberToProto _)
    val seen = gossip.overview.seen.map(mapUniqueAddress)

    val overview =
      cm.GossipOverview.newBuilder.addAllSeen(seen.asJava).addAllObserverReachability(reachability.map(_.build).asJava)

    cm.Gossip
      .newBuilder()
      .addAllAllAddresses(allAddresses.map(uniqueAddressToProto(_).build).asJava)
      .addAllAllRoles(allRoles.asJava)
      .addAllAllHashes(allHashes.asJava)
      .addAllMembers(members.map(_.build).asJava)
      .setOverview(overview)
      .setVersion(vectorClockToProto(gossip.version, hashMapping))
      .addAllTombstones(gossip.tombstones.map(tombstoneToProto _).asJava)
  }

  private def vectorClockToProto(version: VectorClock, hashMapping: Map[String, Int]): cm.VectorClock.Builder = {
    val versions: Iterable[cm.VectorClock.Version.Builder] = version.versions.map {
      case (n, t) =>
        cm.VectorClock.Version.newBuilder().setHashIndex(mapWithErrorMessage(hashMapping, n, "hash")).setTimestamp(t)
    }
    cm.VectorClock.newBuilder().setTimestamp(0).addAllVersions(versions.map(_.build).asJava)
  }

  private def gossipEnvelopeToProto(envelope: GossipEnvelope): cm.GossipEnvelope =
    cm.GossipEnvelope
      .newBuilder()
      .setFrom(uniqueAddressToProto(envelope.from))
      .setTo(uniqueAddressToProto(envelope.to))
      .setSerializedGossip(ByteString.copyFrom(compress(gossipToProto(envelope.gossip).build)))
      .build

  private def gossipStatusToProto(status: GossipStatus): cm.GossipStatus = {
    val allHashes = status.version.versions.keys.toVector
    val hashMapping = allHashes.zipWithIndex.toMap
    cm.GossipStatus
      .newBuilder()
      .setFrom(uniqueAddressToProto(status.from))
      .addAllAllHashes(allHashes.asJava)
      .setVersion(vectorClockToProto(status.version, hashMapping))
      .build()
  }

  private def deserializeGossipEnvelope(bytes: Array[Byte]): GossipEnvelope =
    gossipEnvelopeFromProto(cm.GossipEnvelope.parseFrom(bytes))

  private def deserializeGossipStatus(bytes: Array[Byte]): GossipStatus =
    gossipStatusFromProto(cm.GossipStatus.parseFrom(bytes))

  private def gossipFromProto(gossip: cm.Gossip): Gossip = {
    val addressMapping: Vector[UniqueAddress] =
      gossip.getAllAddressesList.asScala.iterator.map(uniqueAddressFromProto).to(immutable.Vector)
    val roleMapping: Vector[String] = gossip.getAllRolesList.asScala.iterator.map(identity).to(immutable.Vector)
    val hashMapping: Vector[String] = gossip.getAllHashesList.asScala.iterator.map(identity).to(immutable.Vector)

    def reachabilityFromProto(observerReachability: Iterable[cm.ObserverReachability]): Reachability = {
      val recordBuilder = new immutable.VectorBuilder[Reachability.Record]
      val versionsBuilder = Map.newBuilder[UniqueAddress, Long]
      for (o <- observerReachability) {
        val observer = addressMapping(o.getAddressIndex)
        versionsBuilder += ((observer, o.getVersion))
        for (s <- o.getSubjectReachabilityList.asScala) {
          val subject = addressMapping(s.getAddressIndex)
          val record =
            Reachability.Record(observer, subject, reachabilityStatusFromInt(s.getStatus.getNumber), s.getVersion)
          recordBuilder += record
        }
      }

      Reachability.create(recordBuilder.result(), versionsBuilder.result())
    }

    def memberFromProto(member: cm.Member) =
      new Member(
        addressMapping(member.getAddressIndex),
        member.getUpNumber,
        memberStatusFromInt(member.getStatus.getNumber),
        rolesFromProto(member.getRolesIndexesList.asScala.toSeq))

    def rolesFromProto(roleIndexes: Seq[Integer]): Set[String] = {
      var containsDc = false
      var roles = Set.empty[String]

      for {
        roleIndex <- roleIndexes
        role = roleMapping(roleIndex)
      } {
        if (role.startsWith(ClusterSettings.DcRolePrefix)) containsDc = true
        roles += role
      }

      if (!containsDc) roles + (ClusterSettings.DcRolePrefix + ClusterSettings.DefaultDataCenter)
      else roles
    }

    def tombstoneFromProto(tombstone: cm.Tombstone): (UniqueAddress, Long) =
      (addressMapping(tombstone.getAddressIndex), tombstone.getTimestamp)

    val members: immutable.SortedSet[Member] =
      gossip.getMembersList.asScala.iterator.map(memberFromProto).to(immutable.SortedSet)

    val reachability = reachabilityFromProto(gossip.getOverview.getObserverReachabilityList.asScala)
    val seen: Set[UniqueAddress] =
      gossip.getOverview.getSeenList.asScala.iterator.map(addressMapping(_)).to(immutable.Set)
    val overview = GossipOverview(seen, reachability)
    val tombstones: Map[UniqueAddress, Long] = gossip.getTombstonesList.asScala.iterator.map(tombstoneFromProto).toMap

    Gossip(members, overview, vectorClockFromProto(gossip.getVersion, hashMapping), tombstones)
  }

  private def vectorClockFromProto(version: cm.VectorClock, hashMapping: immutable.Seq[String]) = {
    VectorClock(scala.collection.immutable.TreeMap.from(version.getVersionsList.asScala.iterator.map(v =>
      (VectorClock.Node.fromHash(hashMapping(v.getHashIndex)), v.getTimestamp))))
  }

  private def gossipEnvelopeFromProto(envelope: cm.GossipEnvelope): GossipEnvelope = {
    val serializedGossip = envelope.getSerializedGossip
    GossipEnvelope(
      uniqueAddressFromProto(envelope.getFrom),
      uniqueAddressFromProto(envelope.getTo),
      Deadline.now + GossipTimeToLive,
      () => gossipFromProto(cm.Gossip.parseFrom(decompress(serializedGossip.toByteArray))))
  }

  private def gossipStatusFromProto(status: cm.GossipStatus): GossipStatus =
    GossipStatus(
      uniqueAddressFromProto(status.getFrom),
      vectorClockFromProto(status.getVersion, status.getAllHashesList.asScala.toVector))

  def deserializeClusterRouterPool(bytes: Array[Byte]): ClusterRouterPool = {
    val crp = cm.ClusterRouterPool.parseFrom(bytes)

    ClusterRouterPool(poolFromProto(crp.getPool), clusterRouterPoolSettingsFromProto(crp.getSettings))
  }

  private def poolFromProto(pool: cm.Pool): Pool = {
    serialization.deserialize(pool.getData.toByteArray, pool.getSerializerId, pool.getManifest).get.asInstanceOf[Pool]
  }

  private def clusterRouterPoolSettingsFromProto(crps: cm.ClusterRouterPoolSettings): ClusterRouterPoolSettings = {
    // For backwards compatibility, useRoles is the combination of getUseRole and getUseRolesList
    ClusterRouterPoolSettings(
      totalInstances = crps.getTotalInstances,
      maxInstancesPerNode = crps.getMaxInstancesPerNode,
      allowLocalRoutees = crps.getAllowLocalRoutees,
      useRoles = if (crps.hasUseRole) {
        crps.getUseRolesList.asScala.toSet + crps.getUseRole
      } else {
        crps.getUseRolesList.asScala.toSet
      })
  }

}
