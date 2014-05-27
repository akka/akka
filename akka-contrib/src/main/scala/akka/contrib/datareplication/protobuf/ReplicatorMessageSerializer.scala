/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.concurrent.duration.Duration
import com.google.protobuf.ByteString
import akka.actor.ExtendedActorSystem
import akka.cluster.Member
import akka.cluster.UniqueAddress
import akka.contrib.datareplication.PruningState
import akka.contrib.datareplication.ReplicatedData
import akka.contrib.datareplication.Replicator._
import akka.contrib.datareplication.Replicator.Internal._
import akka.contrib.datareplication.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.Serialization
import akka.serialization.Serializer
import akka.util.{ ByteString ⇒ AkkaByteString }

/**
 * Protobuf serializer of ReplicatorMessage messages.
 */
class ReplicatorMessageSerializer(val system: ExtendedActorSystem) extends Serializer with SerializationSupport {

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ReplicatorMessage], Array[Byte] ⇒ AnyRef](
    classOf[Get] -> getFromBinary,
    classOf[GetSuccess] -> getSuccessFromBinary,
    classOf[NotFound] -> notFoundFromBinary,
    classOf[GetFailure] -> getFailureFromBinary,
    classOf[Subscribe] -> subscribeFromBinary,
    classOf[Unsubscribe] -> unsubscribeFromBinary,
    classOf[Changed] -> changedFromBinary,
    classOf[DataEnvelope] -> dataEnvelopeFromBinary,
    classOf[Write] -> writeFromBinary,
    WriteAck.getClass -> (_ ⇒ WriteAck),
    classOf[Read] -> readFromBinary,
    classOf[ReadResult] -> readResultFromBinary,
    classOf[Status] -> statusFromBinary,
    classOf[Gossip] -> gossipFromBinary)

  def includeManifest: Boolean = true

  def identifier = 99901

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: DataEnvelope ⇒ dataEnvelopeToProto(m).toByteArray
    case m: Write        ⇒ writeToProto(m).toByteArray
    case WriteAck        ⇒ dm.Empty.getDefaultInstance.toByteArray
    case m: Read         ⇒ readToProto(m).toByteArray
    case m: ReadResult   ⇒ readResultToProto(m).toByteArray
    case m: Status       ⇒ compress(statusToProto(m))
    case m: Get          ⇒ getToProto(m).toByteArray
    case m: GetSuccess   ⇒ getSuccessToProto(m).toByteArray
    case m: Changed      ⇒ changedToProto(m).toByteArray
    case m: NotFound     ⇒ notFoundToProto(m).toByteArray
    case m: GetFailure   ⇒ getFailureToProto(m).toByteArray
    case m: Subscribe    ⇒ subscribeToProto(m).toByteArray
    case m: Unsubscribe  ⇒ unsubscribeToProto(m).toByteArray
    case m: Gossip       ⇒ compress(gossipToProto(m))
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = clazz match {
    case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ReplicatorMessage]]) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new IllegalArgumentException(
        s"Unimplemented deserialization of message class $c in ReplicatorMessageSerializer")
    }
    case _ ⇒ throw new IllegalArgumentException(
      "Need a message class to be able to deserialize bytes in ReplicatorMessageSerializer")
  }

  private def statusToProto(status: Status): dm.Status = {
    val b = dm.Status.newBuilder()
    val entries = status.digests.foreach {
      case (key, digest) ⇒
        b.addEntries(dm.Status.Entry.newBuilder().
          setKey(key).
          setDigest(ByteString.copyFrom(digest.toArray)))
    }
    b.build()
  }

  private def statusFromBinary(bytes: Array[Byte]): Status = {
    val status = dm.Status.parseFrom(decompress(bytes))
    Status(status.getEntriesList.asScala.map(e ⇒
      e.getKey -> AkkaByteString(e.getDigest.toByteArray()))(breakOut))
  }

  private def gossipToProto(gossip: Gossip): dm.Gossip = {
    val b = dm.Gossip.newBuilder()
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
    Gossip(gossip.getEntriesList.asScala.map(e ⇒
      e.getKey -> dataEnvelopeFromProto(e.getEnvelope))(breakOut))
  }

  private def getToProto(get: Get): dm.Get = {
    val consistencyValue = get.consistency match {
      case ReadFrom(n) ⇒ n
      case ReadQuorum  ⇒ 0
      case ReadAll     ⇒ -1
    }

    val b = dm.Get.newBuilder().
      setKey(get.key).
      setConsistency(consistencyValue).
      setTimeout(get.timeout.toMillis.toInt)

    get.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getFromBinary(bytes: Array[Byte]): Get = {
    val get = dm.Get.parseFrom(bytes)
    val request = if (get.hasRequest()) Some(otherMessageFromProto(get.getRequest)) else None
    val consistency = get.getConsistency match {
      case 0  ⇒ ReadQuorum
      case -1 ⇒ ReadAll
      case n  ⇒ ReadFrom(n)
    }
    Get(get.getKey, consistency, Duration(get.getTimeout, TimeUnit.MILLISECONDS), request)
  }

  private def getSuccessToProto(getSuccess: GetSuccess): dm.GetSuccess = {
    val b = dm.GetSuccess.newBuilder().
      setKey(getSuccess.key).
      setData(otherMessageToProto(getSuccess.data)).
      setSeqNo(getSuccess.seqNo)

    getSuccess.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getSuccessFromBinary(bytes: Array[Byte]): GetSuccess = {
    val getSuccess = dm.GetSuccess.parseFrom(bytes)
    val request = if (getSuccess.hasRequest()) Some(otherMessageFromProto(getSuccess.getRequest)) else None
    val data = otherMessageFromProto(getSuccess.getData).asInstanceOf[ReplicatedData]
    GetSuccess(getSuccess.getKey, data, getSuccess.getSeqNo, request)
  }

  private def notFoundToProto(notFound: NotFound): dm.NotFound = {
    val b = dm.NotFound.newBuilder().setKey(notFound.key)
    notFound.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def notFoundFromBinary(bytes: Array[Byte]): NotFound = {
    val notFound = dm.NotFound.parseFrom(bytes)
    val request = if (notFound.hasRequest()) Some(otherMessageFromProto(notFound.getRequest)) else None
    NotFound(notFound.getKey, request)
  }

  private def getFailureToProto(getFailure: GetFailure): dm.GetFailure = {
    val b = dm.GetFailure.newBuilder().setKey(getFailure.key)
    getFailure.request.foreach(o ⇒ b.setRequest(otherMessageToProto(o)))
    b.build()
  }

  private def getFailureFromBinary(bytes: Array[Byte]): GetFailure = {
    val getFailure = dm.GetFailure.parseFrom(bytes)
    val request = if (getFailure.hasRequest()) Some(otherMessageFromProto(getFailure.getRequest)) else None
    GetFailure(getFailure.getKey, request)
  }

  private def subscribeToProto(subscribe: Subscribe): dm.Subscribe =
    dm.Subscribe.newBuilder().
      setKey(subscribe.key).
      setRef(Serialization.serializedActorPath(subscribe.subscriber)).
      build()

  private def subscribeFromBinary(bytes: Array[Byte]): Subscribe = {
    val subscribe = dm.Subscribe.parseFrom(bytes)
    Subscribe(subscribe.getKey, resolveActorRef(subscribe.getRef))
  }

  private def unsubscribeToProto(unsubscribe: Unsubscribe): dm.Unsubscribe =
    dm.Unsubscribe.newBuilder().
      setKey(unsubscribe.key).
      setRef(Serialization.serializedActorPath(unsubscribe.subscriber)).
      build()

  private def unsubscribeFromBinary(bytes: Array[Byte]): Unsubscribe = {
    val unsubscribe = dm.Unsubscribe.parseFrom(bytes)
    Unsubscribe(unsubscribe.getKey, resolveActorRef(unsubscribe.getRef))
  }

  private def changedToProto(changed: Changed): dm.Changed =
    dm.Changed.newBuilder().
      setKey(changed.key).
      setData(otherMessageToProto(changed.data)).
      build()

  private def changedFromBinary(bytes: Array[Byte]): Changed = {
    val changed = dm.Changed.parseFrom(bytes)
    val data = otherMessageFromProto(changed.getData).asInstanceOf[ReplicatedData]
    Changed(changed.getKey, data)
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
        removed -> state
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

}
