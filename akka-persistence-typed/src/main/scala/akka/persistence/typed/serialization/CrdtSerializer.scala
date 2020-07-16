/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.serialization

import java.io.NotSerializableException
import java.util.{ ArrayList, Collections, Comparator }
import java.{ lang => jl }

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.persistence.typed.crdt.{ Counter, ORSet }
import akka.persistence.typed.internal.VersionVector
import akka.protobufv3.internal.ByteString
import akka.remote.ContainerFormats.Payload
import akka.remote.serialization.WrappedPayloadSupport
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap

object CrdtSerializer {
  object Comparator extends Comparator[Payload] {
    override def compare(a: Payload, b: Payload): Int = {
      val aByteString = a.getEnclosedMessage
      val bByteString = b.getEnclosedMessage
      val aSize = aByteString.size
      val bSize = bByteString.size
      if (aSize == bSize) {
        val aIter = aByteString.iterator
        val bIter = bByteString.iterator
        @tailrec def findDiff(): Int = {
          if (aIter.hasNext) {
            val aByte = aIter.nextByte()
            val bByte = bIter.nextByte()
            if (aByte < bByte) -1
            else if (aByte > bByte) 1
            else findDiff()
          } else 0
        }
        findDiff()
      } else if (aSize < bSize) -1
      else 1
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class CrdtSerializer(val system: ExtendedActorSystem)
    extends SerializerWithStringManifest
    with BaseSerializer {

  private val wrappedSupport = new WrappedPayloadSupport(system)

  private val CrdtCounterManifest = "AA"
  private val CrdtCounterUpdatedManifest = "AB"

  private val ORSetManifest = "CA"
  private val ORSetAddManifest = "CB"
  private val ORSetRemoveManifest = "CC"
  private val ORSetFullManifest = "CD"
  private val ORSetDeltaGroupManifest = "CE"

  private val VersionVectorManifest = "DA"

  def manifest(o: AnyRef) = o match {
    case _: ORSet[_]                  => ORSetManifest
    case _: ORSet.AddDeltaOp[_]       => ORSetAddManifest
    case _: ORSet.RemoveDeltaOp[_]    => ORSetRemoveManifest
    case _: ORSet.DeltaGroup[_]       => ORSetDeltaGroupManifest
    case _: ORSet.FullStateDeltaOp[_] => ORSetFullManifest

    case _: Counter         => CrdtCounterManifest
    case _: Counter.Updated => CrdtCounterUpdatedManifest

    case _: VersionVector => VersionVectorManifest
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
  }

  def toBinary(o: AnyRef) = o match {
    case m: ORSet[_]                  => orsetToProto(m).toByteArray
    case m: ORSet.AddDeltaOp[_]       => orsetToProto(m.underlying).toByteArray
    case m: ORSet.RemoveDeltaOp[_]    => orsetToProto(m.underlying).toByteArray
    case m: ORSet.DeltaGroup[_]       => orsetDeltaGroupToProto(m).toByteArray
    case m: ORSet.FullStateDeltaOp[_] => orsetToProto(m.underlying).toByteArray

    case m: Counter         => counterToProtoByteArray(m)
    case m: Counter.Updated => counterUpdatedToProtoBufByteArray(m)
    case m: VersionVector   => versionVectorToProto(m).toByteArray
    case _ =>
      throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass}")
  }

  def fromBinary(bytes: Array[Byte], manifest: String) = manifest match {
    case ORSetManifest           => orsetFromBinary(bytes)
    case ORSetAddManifest        => orsetAddFromBinary(bytes)
    case ORSetRemoveManifest     => orsetRemoveFromBinary(bytes)
    case ORSetFullManifest       => orsetFullFromBinary(bytes)
    case ORSetDeltaGroupManifest => orsetDeltaGroupFromBinary(bytes)

    case CrdtCounterManifest        => counterFromBinary(bytes)
    case CrdtCounterUpdatedManifest => counterUpdatedFromBinary(bytes)

    case VersionVectorManifest => versionVectorFromBinary(bytes)
    case _ =>
      throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
  }

  def counterFromBinary(bytes: Array[Byte]): Counter =
    Counter(BigInt(Crdts.Counter.parseFrom(bytes).getValue.toByteArray))

  def counterUpdatedFromBinary(bytes: Array[Byte]): Counter.Updated =
    Counter.Updated(BigInt(Crdts.CounterUpdate.parseFrom(bytes).getDelta.toByteArray))

  def counterToProtoByteArray(counter: Counter): Array[Byte] =
    Crdts.Counter.newBuilder().setValue(ByteString.copyFrom(counter.value.toByteArray)).build().toByteArray

  def counterUpdatedToProtoBufByteArray(updated: Counter.Updated): Array[Byte] =
    Crdts.CounterUpdate.newBuilder().setDelta(ByteString.copyFrom(updated.delta.toByteArray)).build().toByteArray

  def orsetToProto(orset: ORSet[_]): Crdts.ORSet =
    orsetToProtoImpl(orset.asInstanceOf[ORSet[Any]])

  private def orsetToProtoImpl(orset: ORSet[Any]): Crdts.ORSet = {
    val b = Crdts.ORSet.newBuilder().setOriginDc(orset.originReplica).setVvector(versionVectorToProto(orset.vvector))
    // using java collections and sorting for performance (avoid conversions)
    val stringElements = new ArrayList[String]
    val intElements = new ArrayList[Integer]
    val longElements = new ArrayList[jl.Long]
    val otherElements = new ArrayList[Payload]
    var otherElementsMap = Map.empty[Payload, Any]
    orset.elementsMap.keysIterator.foreach {
      case s: String => stringElements.add(s)
      case i: Int    => intElements.add(i)
      case l: Long   => longElements.add(l)
      case other =>
        val enclosedMsg = wrappedSupport.payloadBuilder(other).build()
        otherElements.add(enclosedMsg)
        // need the mapping back to the `other` when adding dots
        otherElementsMap = otherElementsMap.updated(enclosedMsg, other)
    }

    def addDots(elements: ArrayList[_]): Unit = {
      // add corresponding dots in same order
      val iter = elements.iterator
      while (iter.hasNext) {
        val element = iter.next() match {
          case enclosedMsg: Payload => otherElementsMap(enclosedMsg)
          case e                    => e
        }
        b.addDots(versionVectorToProto(orset.elementsMap(element)))
      }
    }

    if (!stringElements.isEmpty) {
      Collections.sort(stringElements)
      b.addAllStringElements(stringElements)
      addDots(stringElements)
    }
    if (!intElements.isEmpty) {
      Collections.sort(intElements)
      b.addAllIntElements(intElements)
      addDots(intElements)
    }
    if (!longElements.isEmpty) {
      Collections.sort(longElements)
      b.addAllLongElements(longElements)
      addDots(longElements)
    }
    if (!otherElements.isEmpty) {
      Collections.sort(otherElements, CrdtSerializer.Comparator)
      b.addAllOtherElements(otherElements)
      addDots(otherElements)
    }

    b.build()
  }

  def orsetFromBinary(bytes: Array[Byte]): ORSet[Any] =
    orsetFromProto(Crdts.ORSet.parseFrom(bytes))

  private def orsetAddFromBinary(bytes: Array[Byte]): ORSet.AddDeltaOp[Any] =
    new ORSet.AddDeltaOp(orsetFromProto(Crdts.ORSet.parseFrom(bytes)))

  private def orsetRemoveFromBinary(bytes: Array[Byte]): ORSet.RemoveDeltaOp[Any] =
    new ORSet.RemoveDeltaOp(orsetFromProto(Crdts.ORSet.parseFrom(bytes)))

  private def orsetFullFromBinary(bytes: Array[Byte]): ORSet.FullStateDeltaOp[Any] =
    new ORSet.FullStateDeltaOp(orsetFromProto(Crdts.ORSet.parseFrom(bytes)))

  private def orsetDeltaGroupToProto(deltaGroup: ORSet.DeltaGroup[_]): Crdts.ORSetDeltaGroup = {
    def createEntry(opType: Crdts.ORSetDeltaOp, u: ORSet[_]) = {
      Crdts.ORSetDeltaGroup.Entry.newBuilder().setOperation(opType).setUnderlying(orsetToProto(u))
    }

    val b = Crdts.ORSetDeltaGroup.newBuilder()
    deltaGroup.ops.foreach {
      case ORSet.AddDeltaOp(u) =>
        b.addEntries(createEntry(Crdts.ORSetDeltaOp.Add, u))
      case ORSet.RemoveDeltaOp(u) =>
        b.addEntries(createEntry(Crdts.ORSetDeltaOp.Remove, u))
      case ORSet.FullStateDeltaOp(u) =>
        b.addEntries(createEntry(Crdts.ORSetDeltaOp.Full, u))
      case ORSet.DeltaGroup(_) =>
        throw new IllegalArgumentException("ORSet.DeltaGroup should not be nested")
    }
    b.build()
  }

  private def orsetDeltaGroupFromBinary(bytes: Array[Byte]): ORSet.DeltaGroup[Any] = {
    val deltaGroup = Crdts.ORSetDeltaGroup.parseFrom(bytes)
    val ops: Vector[ORSet.DeltaOp] =
      deltaGroup.getEntriesList.asScala.map { entry =>
        if (entry.getOperation == Crdts.ORSetDeltaOp.Add)
          ORSet.AddDeltaOp(orsetFromProto(entry.getUnderlying))
        else if (entry.getOperation == Crdts.ORSetDeltaOp.Remove)
          ORSet.RemoveDeltaOp(orsetFromProto(entry.getUnderlying))
        else if (entry.getOperation == Crdts.ORSetDeltaOp.Full)
          ORSet.FullStateDeltaOp(orsetFromProto(entry.getUnderlying))
        else
          throw new NotSerializableException(s"Unknow ORSet delta operation ${entry.getOperation}")
      }.toVector
    ORSet.DeltaGroup(ops)
  }

  def orsetFromProto(orset: Crdts.ORSet): ORSet[Any] = {
    val elements: Iterator[Any] =
      (orset.getStringElementsList.iterator.asScala ++
      orset.getIntElementsList.iterator.asScala ++
      orset.getLongElementsList.iterator.asScala ++
      orset.getOtherElementsList.iterator.asScala.map(wrappedSupport.deserializePayload))

    val dots = orset.getDotsList.asScala.map(versionVectorFromProto).iterator
    val elementsMap = elements.zip(dots).toMap

    new ORSet(orset.getOriginDc, elementsMap, vvector = versionVectorFromProto(orset.getVvector))
  }

  def versionVectorToProto(versionVector: VersionVector): Crdts.VersionVector = {
    val b = Crdts.VersionVector.newBuilder()
    versionVector.versionsIterator.foreach {
      case (key, value) => b.addEntries(Crdts.VersionVector.Entry.newBuilder().setKey(key).setVersion(value))
    }
    b.build()
  }

  def versionVectorFromBinary(bytes: Array[Byte]): VersionVector =
    versionVectorFromProto(Crdts.VersionVector.parseFrom(bytes))

  def versionVectorFromProto(versionVector: Crdts.VersionVector): VersionVector = {
    val entries = versionVector.getEntriesList
    if (entries.isEmpty)
      VersionVector.empty
    else if (entries.size == 1)
      VersionVector(entries.get(0).getKey, entries.get(0).getVersion)
    else {
      val versions = TreeMap.empty[String, Long] ++ versionVector.getEntriesList.asScala.map(entry =>
          entry.getKey -> entry.getVersion)
      VersionVector(versions)
    }
  }

}
