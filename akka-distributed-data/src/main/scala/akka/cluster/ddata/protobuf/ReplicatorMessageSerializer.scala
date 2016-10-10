/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata.protobuf

import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.duration.Duration
import akka.actor.ExtendedActorSystem
import akka.cluster.Member
import akka.cluster.UniqueAddress
import akka.cluster.ddata.PruningState
import akka.cluster.ddata.ReplicatedData
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.Replicator.Internal._
import akka.cluster.ddata.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.Serialization
import akka.serialization.SerializerWithStringManifest
import akka.serialization.BaseSerializer
import akka.util.{ ByteString ⇒ AkkaByteString }
import akka.protobuf.ByteString
import akka.cluster.ddata.Key.KeyR
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import akka.cluster.ddata.DurableStore.DurableDataEnvelope
import akka.cluster.ddata.DurableStore.DurableDataEnvelope

/**
 * INTERNAL API
 */
private[akka] object ReplicatorMessageSerializer {

  /**
   * A cache that is designed for a small number (&lt;= 32) of
   * entries. It is using instance equality.
   * Adding new entry overwrites oldest. It is
   * thread safe but duplicates of same entry may occur.
   *
   * `evict` must be called from the outside, i.e. the
   * cache will not cleanup itself.
   */
  final class SmallCache[A <: AnyRef, B <: AnyRef](size: Int, timeToLive: FiniteDuration, getOrAddFactory: A ⇒ B) {
    require((size & (size - 1)) == 0, "size must be a power of 2")
    require(size <= 32, "size must be <= 32")

    private val n = new AtomicInteger(0)
    private val mask = size - 1
    private val elements = Array.ofDim[(A, B)](size)
    private val ttlNanos = timeToLive.toNanos

    // in theory this should be volatile, but since the cache has low
    // guarantees anyway and visibility will be synced by other activity
    // so we use non-volatile
    private var lastUsed = System.nanoTime()

    /**
     * Get value from cache or `null` if it doesn't exist.
     */
    def get(a: A): B = get(a, n.get)

    private def get(a: A, startPos: Int): B = {
      // start at the latest added value, most likely that we want that
      val end = startPos + elements.length
      @tailrec def find(i: Int): B = {
        if (end - i == 0) null.asInstanceOf[B]
        else {
          val x = elements(i & mask)
          if ((x eq null) || (x._1 ne a)) find(i + 1)
          else x._2
        }
      }
      lastUsed = System.nanoTime()
      find(startPos)
    }

    /**
     * Add entry to the cache.
     * Overwrites oldest entry.
     */
    def add(a: A, b: B): Unit = add((a, b))

    def add(x: (A, B)): Unit = {
      val i = n.incrementAndGet()
      elements(i & mask) = x
      lastUsed = System.nanoTime()
    }

    /**
     * Get value from cache or create new value with the
     * `getOrAddFactory` that was given in the constructor. The new
     * value is added to the cache. Duplicates of same value may be added
     * if multiple threads call this concurrently, but decent
     * (low cost) effort is made to reduce the chance of duplicates.
     */
    def getOrAdd(a: A): B = {
      val position = n.get
      val c = get(a, position)
      if (c ne null)
        c
      else {
        val b2 = getOrAddFactory(a)
        if (position == n.get) {
          // no change, add the new value
          add(a, b2)
          b2
        } else {
          // some other thread added, try one more time
          // to reduce duplicates
          val c2 = get(a)
          if (c2 ne null) c2 // found it
          else {
            add(a, b2)
            b2
          }
        }
      }
    }

    /**
     * Remove all elements if the cache has not been
     * used within the `timeToLive`.
     */
    def evict(): Unit =
      if ((System.nanoTime() - lastUsed) > ttlNanos) {
        var i = 0
        while (i < elements.length) {
          elements(i) = null
          i += 1
        }
      }

    override def toString: String =
      elements.mkString("[", ",", "]")
  }
}

/**
 * Protobuf serializer of ReplicatorMessage messages.
 */
class ReplicatorMessageSerializer(val system: ExtendedActorSystem)
  extends SerializerWithStringManifest with SerializationSupport with BaseSerializer {
  import ReplicatorMessageSerializer.SmallCache

  private val cacheTimeToLive = system.settings.config.getDuration(
    "akka.cluster.distributed-data.serializer-cache-time-to-live", TimeUnit.MILLISECONDS).millis
  private val readCache = new SmallCache[Read, Array[Byte]](4, cacheTimeToLive, m ⇒ readToProto(m).toByteArray)
  private val writeCache = new SmallCache[Write, Array[Byte]](4, cacheTimeToLive, m ⇒ writeToProto(m).toByteArray)
  system.scheduler.schedule(cacheTimeToLive, cacheTimeToLive / 2) {
    readCache.evict()
    writeCache.evict()
  }(system.dispatcher)

  private val writeAckBytes = dm.Empty.getDefaultInstance.toByteArray

  val GetManifest = "A"
  val GetSuccessManifest = "B"
  val NotFoundManifest = "C"
  val GetFailureManifest = "D"
  val SubscribeManifest = "E"
  val UnsubscribeManifest = "F"
  val ChangedManifest = "G"
  val DataEnvelopeManifest = "H"
  val WriteManifest = "I"
  val WriteAckManifest = "J"
  val ReadManifest = "K"
  val ReadResultManifest = "L"
  val StatusManifest = "M"
  val GossipManifest = "N"
  val WriteNackManifest = "O"
  val DurableDataEnvelopeManifest = "P"

  private val fromBinaryMap = collection.immutable.HashMap[String, Array[Byte] ⇒ AnyRef](
    GetManifest → getFromBinary,
    GetSuccessManifest → getSuccessFromBinary,
    NotFoundManifest → notFoundFromBinary,
    GetFailureManifest → getFailureFromBinary,
    SubscribeManifest → subscribeFromBinary,
    UnsubscribeManifest → unsubscribeFromBinary,
    ChangedManifest → changedFromBinary,
    DataEnvelopeManifest → dataEnvelopeFromBinary,
    WriteManifest → writeFromBinary,
    WriteAckManifest → (_ ⇒ WriteAck),
    ReadManifest → readFromBinary,
    ReadResultManifest → readResultFromBinary,
    StatusManifest → statusFromBinary,
    GossipManifest → gossipFromBinary,
    WriteNackManifest → (_ ⇒ WriteNack),
    DurableDataEnvelopeManifest → durableDataEnvelopeFromBinary)

  override def manifest(obj: AnyRef): String = obj match {
    case _: DataEnvelope        ⇒ DataEnvelopeManifest
    case _: Write               ⇒ WriteManifest
    case WriteAck               ⇒ WriteAckManifest
    case _: Read                ⇒ ReadManifest
    case _: ReadResult          ⇒ ReadResultManifest
    case _: Status              ⇒ StatusManifest
    case _: Get[_]              ⇒ GetManifest
    case _: GetSuccess[_]       ⇒ GetSuccessManifest
    case _: DurableDataEnvelope ⇒ DurableDataEnvelopeManifest
    case _: Changed[_]          ⇒ ChangedManifest
    case _: NotFound[_]         ⇒ NotFoundManifest
    case _: GetFailure[_]       ⇒ GetFailureManifest
    case _: Subscribe[_]        ⇒ SubscribeManifest
    case _: Unsubscribe[_]      ⇒ UnsubscribeManifest
    case _: Gossip              ⇒ GossipManifest
    case WriteNack              ⇒ WriteNackManifest
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: DataEnvelope        ⇒ dataEnvelopeToProto(m).toByteArray
    case m: Write               ⇒ writeCache.getOrAdd(m)
    case WriteAck               ⇒ writeAckBytes
    case m: Read                ⇒ readCache.getOrAdd(m)
    case m: ReadResult          ⇒ readResultToProto(m).toByteArray
    case m: Status              ⇒ statusToProto(m).toByteArray
    case m: Get[_]              ⇒ getToProto(m).toByteArray
    case m: GetSuccess[_]       ⇒ getSuccessToProto(m).toByteArray
    case m: DurableDataEnvelope ⇒ durableDataEnvelopeToProto(m).toByteArray
    case m: Changed[_]          ⇒ changedToProto(m).toByteArray
    case m: NotFound[_]         ⇒ notFoundToProto(m).toByteArray
    case m: GetFailure[_]       ⇒ getFailureToProto(m).toByteArray
    case m: Subscribe[_]        ⇒ subscribeToProto(m).toByteArray
    case m: Unsubscribe[_]      ⇒ unsubscribeToProto(m).toByteArray
    case m: Gossip              ⇒ compress(gossipToProto(m))
    case WriteNack              ⇒ dm.Empty.getDefaultInstance.toByteArray
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new IllegalArgumentException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  private def statusToProto(status: Status): dm.Status = {
    val b = dm.Status.newBuilder()
    b.setChunk(status.chunk).setTotChunks(status.totChunks)
    val entries = status.digests.foreach {
      case (key, digest) ⇒
        b.addEntries(dm.Status.Entry.newBuilder().
          setKey(key).
          setDigest(ByteString.copyFrom(digest.toArray)))
    }
    b.build()
  }

  private def statusFromBinary(bytes: Array[Byte]): Status = {
    val status = dm.Status.parseFrom(bytes)
    Status(
      status.getEntriesList.asScala.map(e ⇒
        e.getKey → AkkaByteString(e.getDigest.toByteArray()))(breakOut),
      status.getChunk, status.getTotChunks)
  }

  private def gossipToProto(gossip: Gossip): dm.Gossip = {
    val b = dm.Gossip.newBuilder().setSendBack(gossip.sendBack)
    val entries = gossip.updatedData.foreach {
      case (key, data) ⇒
        b.addEntries(dm.Gossip.Entry.newBuilder().
          setKey(key).
          setEnvelope(dataEnvelopeToProto(data)))
    }
    b.build()
  }

  private def gossipFromBinary(bytes: Array[Byte]): Gossip = {
    val gossip = dm.Gossip.parseFrom(decompress(bytes))
    Gossip(
      gossip.getEntriesList.asScala.map(e ⇒
        e.getKey → dataEnvelopeFromProto(e.getEnvelope))(breakOut),
      sendBack = gossip.getSendBack)
  }

  private def getToProto(get: Get[_]): dm.Get = {
    val consistencyValue = get.consistency match {
      case ReadLocal       ⇒ 1
      case ReadFrom(n, _)  ⇒ n
      case _: ReadMajority ⇒ 0
      case _: ReadAll      ⇒ -1
    }

    val b = dm.Get.newBuilder().
      setKey(otherMessageToProto(get.key)).
      setConsistency(consistencyValue).
      setTimeout(get.consistency.timeout.toMillis.toInt)

    get.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getFromBinary(bytes: Array[Byte]): Get[_] = {
    val get = dm.Get.parseFrom(bytes)
    val key = otherMessageFromProto(get.getKey).asInstanceOf[KeyR]
    val request = if (get.hasRequest()) Some(otherMessageFromProto(get.getRequest)) else None
    val timeout = Duration(get.getTimeout, TimeUnit.MILLISECONDS)
    val consistency = get.getConsistency match {
      case 0  ⇒ ReadMajority(timeout)
      case -1 ⇒ ReadAll(timeout)
      case 1  ⇒ ReadLocal
      case n  ⇒ ReadFrom(n, timeout)
    }
    Get(key, consistency, request)
  }

  private def getSuccessToProto(getSuccess: GetSuccess[_]): dm.GetSuccess = {
    val b = dm.GetSuccess.newBuilder().
      setKey(otherMessageToProto(getSuccess.key)).
      setData(otherMessageToProto(getSuccess.dataValue))

    getSuccess.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getSuccessFromBinary(bytes: Array[Byte]): GetSuccess[_] = {
    val getSuccess = dm.GetSuccess.parseFrom(bytes)
    val key = otherMessageFromProto(getSuccess.getKey).asInstanceOf[KeyR]
    val request = if (getSuccess.hasRequest()) Some(otherMessageFromProto(getSuccess.getRequest)) else None
    val data = otherMessageFromProto(getSuccess.getData).asInstanceOf[ReplicatedData]
    GetSuccess(key, request)(data)
  }

  private def notFoundToProto(notFound: NotFound[_]): dm.NotFound = {
    val b = dm.NotFound.newBuilder().setKey(otherMessageToProto(notFound.key))
    notFound.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def notFoundFromBinary(bytes: Array[Byte]): NotFound[_] = {
    val notFound = dm.NotFound.parseFrom(bytes)
    val request = if (notFound.hasRequest()) Some(otherMessageFromProto(notFound.getRequest)) else None
    val key = otherMessageFromProto(notFound.getKey).asInstanceOf[KeyR]
    NotFound(key, request)
  }

  private def getFailureToProto(getFailure: GetFailure[_]): dm.GetFailure = {
    val b = dm.GetFailure.newBuilder().setKey(otherMessageToProto(getFailure.key))
    getFailure.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getFailureFromBinary(bytes: Array[Byte]): GetFailure[_] = {
    val getFailure = dm.GetFailure.parseFrom(bytes)
    val request = if (getFailure.hasRequest()) Some(otherMessageFromProto(getFailure.getRequest)) else None
    val key = otherMessageFromProto(getFailure.getKey).asInstanceOf[KeyR]
    GetFailure(key, request)
  }

  private def subscribeToProto(subscribe: Subscribe[_]): dm.Subscribe =
    dm.Subscribe.newBuilder().
      setKey(otherMessageToProto(subscribe.key)).
      setRef(Serialization.serializedActorPath(subscribe.subscriber)).
      build()

  private def subscribeFromBinary(bytes: Array[Byte]): Subscribe[_] = {
    val subscribe = dm.Subscribe.parseFrom(bytes)
    val key = otherMessageFromProto(subscribe.getKey).asInstanceOf[KeyR]
    Subscribe(key, resolveActorRef(subscribe.getRef))
  }

  private def unsubscribeToProto(unsubscribe: Unsubscribe[_]): dm.Unsubscribe =
    dm.Unsubscribe.newBuilder().
      setKey(otherMessageToProto(unsubscribe.key)).
      setRef(Serialization.serializedActorPath(unsubscribe.subscriber)).
      build()

  private def unsubscribeFromBinary(bytes: Array[Byte]): Unsubscribe[_] = {
    val unsubscribe = dm.Unsubscribe.parseFrom(bytes)
    val key = otherMessageFromProto(unsubscribe.getKey).asInstanceOf[KeyR]
    Unsubscribe(key, resolveActorRef(unsubscribe.getRef))
  }

  private def changedToProto(changed: Changed[_]): dm.Changed =
    dm.Changed.newBuilder().
      setKey(otherMessageToProto(changed.key)).
      setData(otherMessageToProto(changed.dataValue)).
      build()

  private def changedFromBinary(bytes: Array[Byte]): Changed[_] = {
    val changed = dm.Changed.parseFrom(bytes)
    val data = otherMessageFromProto(changed.getData).asInstanceOf[ReplicatedData]
    val key = otherMessageFromProto(changed.getKey).asInstanceOf[KeyR]
    Changed(key)(data)
  }

  private def dataEnvelopeToProto(dataEnvelope: DataEnvelope): dm.DataEnvelope = {
    val dataEnvelopeBuilder = dm.DataEnvelope.newBuilder().
      setData(otherMessageToProto(dataEnvelope.data))
    dataEnvelope.pruning.foreach {
      case (removedAddress, state) ⇒
        val b = dm.DataEnvelope.PruningEntry.newBuilder().
          setRemovedAddress(uniqueAddressToProto(removedAddress)).
          setOwnerAddress(uniqueAddressToProto(state.owner))
        state.phase match {
          case PruningState.PruningInitialized(seen) ⇒
            seen.toVector.sorted(Member.addressOrdering).map(addressToProto).foreach { a ⇒ b.addSeen(a) }
            b.setPerformed(false)
          case PruningState.PruningPerformed ⇒
            b.setPerformed(true)
        }
        dataEnvelopeBuilder.addPruning(b)
    }
    dataEnvelopeBuilder.build()
  }

  private def dataEnvelopeFromBinary(bytes: Array[Byte]): DataEnvelope =
    dataEnvelopeFromProto(dm.DataEnvelope.parseFrom(bytes))

  private def dataEnvelopeFromProto(dataEnvelope: dm.DataEnvelope): DataEnvelope = {
    val pruning: Map[UniqueAddress, PruningState] =
      dataEnvelope.getPruningList.asScala.map { pruningEntry ⇒
        val phase =
          if (pruningEntry.getPerformed) PruningState.PruningPerformed
          else PruningState.PruningInitialized(pruningEntry.getSeenList.asScala.map(addressFromProto)(breakOut))
        val state = PruningState(uniqueAddressFromProto(pruningEntry.getOwnerAddress), phase)
        val removed = uniqueAddressFromProto(pruningEntry.getRemovedAddress)
        removed → state
      }(breakOut)
    val data = otherMessageFromProto(dataEnvelope.getData).asInstanceOf[ReplicatedData]
    DataEnvelope(data, pruning)
  }

  private def writeToProto(write: Write): dm.Write =
    dm.Write.newBuilder().
      setKey(write.key).
      setEnvelope(dataEnvelopeToProto(write.envelope)).
      build()

  private def writeFromBinary(bytes: Array[Byte]): Write = {
    val write = dm.Write.parseFrom(bytes)
    Write(write.getKey, dataEnvelopeFromProto(write.getEnvelope))
  }

  private def readToProto(read: Read): dm.Read =
    dm.Read.newBuilder().setKey(read.key).build()

  private def readFromBinary(bytes: Array[Byte]): Read =
    Read(dm.Read.parseFrom(bytes).getKey)

  private def readResultToProto(readResult: ReadResult): dm.ReadResult = {
    val b = dm.ReadResult.newBuilder()
    readResult.envelope match {
      case Some(d) ⇒ b.setEnvelope(dataEnvelopeToProto(d))
      case None    ⇒
    }
    b.build()
  }

  private def readResultFromBinary(bytes: Array[Byte]): ReadResult = {
    val readResult = dm.ReadResult.parseFrom(bytes)
    val envelope =
      if (readResult.hasEnvelope) Some(dataEnvelopeFromProto(readResult.getEnvelope))
      else None
    ReadResult(envelope)
  }

  private def durableDataEnvelopeToProto(durableDataEnvelope: DurableDataEnvelope): dm.DurableDataEnvelope = {
    dm.DurableDataEnvelope.newBuilder()
      .setData(otherMessageToProto(durableDataEnvelope.data))
      .build()
  }

  private def durableDataEnvelopeFromBinary(bytes: Array[Byte]): DurableDataEnvelope =
    durableDataEnvelopeFromProto(dm.DurableDataEnvelope.parseFrom(bytes))

  private def durableDataEnvelopeFromProto(durableDataEnvelope: dm.DurableDataEnvelope): DurableDataEnvelope = {
    val data = otherMessageFromProto(durableDataEnvelope.getData).asInstanceOf[ReplicatedData]
    new DurableDataEnvelope(data)
  }

}
