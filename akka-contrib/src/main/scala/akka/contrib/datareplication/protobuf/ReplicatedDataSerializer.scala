/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication.protobuf

import java.{ lang ⇒ jl }
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.breakOut
import akka.actor.ExtendedActorSystem
import akka.contrib.datareplication.Flag
import akka.contrib.datareplication.GCounter
import akka.contrib.datareplication.GSet
import akka.contrib.datareplication.LWWMap
import akka.contrib.datareplication.LWWRegister
import akka.contrib.datareplication.ORMap
import akka.contrib.datareplication.ORSet
import akka.contrib.datareplication.PNCounter
import akka.contrib.datareplication.PNCounterMap
import akka.contrib.datareplication.ReplicatedData
import akka.contrib.datareplication.Replicator._
import akka.contrib.datareplication.Replicator.Internal._
import akka.contrib.datareplication.protobuf.msg.{ ReplicatedDataMessages ⇒ rd }
import akka.contrib.datareplication.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.Serializer
import akka.contrib.datareplication.VectorClock

/**
 * Protobuf serializer of ReplicatedData.
 */
class ReplicatedDataSerializer(val system: ExtendedActorSystem) extends Serializer with SerializationSupport {

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ReplicatedData], Array[Byte] ⇒ AnyRef](
    classOf[GSet] -> gsetFromBinary,
    classOf[ORSet] -> orsetFromBinary,
    classOf[Flag] -> flagFromBinary,
    classOf[LWWRegister] -> lwwRegisterFromBinary,
    classOf[GCounter] -> gcounterFromBinary,
    classOf[PNCounter] -> pncounterFromBinary,
    classOf[ORMap] -> ormapFromBinary,
    classOf[LWWMap] -> lwwmapFromBinary,
    classOf[PNCounterMap] -> pncountermapFromBinary,
    DeletedData.getClass -> (_ ⇒ DeletedData),
    classOf[VectorClock] -> vectorClockFromBinary)

  def includeManifest: Boolean = true

  def identifier = 99902

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: ORSet        ⇒ orsetToProto(m).toByteArray
    case m: GSet         ⇒ gsetToProto(m).toByteArray
    case m: GCounter     ⇒ gcounterToProto(m).toByteArray
    case m: PNCounter    ⇒ pncounterToProto(m).toByteArray
    case m: Flag         ⇒ flagToProto(m).toByteArray
    case m: LWWRegister  ⇒ lwwRegisterToProto(m).toByteArray
    case m: ORMap        ⇒ compress(ormapToProto(m))
    case m: LWWMap       ⇒ compress(lwwmapToProto(m))
    case m: PNCounterMap ⇒ compress(pncountermapToProto(m))
    case DeletedData     ⇒ dm.Empty.getDefaultInstance.toByteArray
    case m: VectorClock  ⇒ vectorClockToProto(m).toByteArray
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = clazz match {
    case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ReplicatedData]]) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new IllegalArgumentException(
        s"Unimplemented deserialization of message class $c in ReplicatedDataSerializer")
    }
    case _ ⇒ throw new IllegalArgumentException(
      "Need a message class to be able to deserialize bytes in ReplicatedDataSerializer")
  }

  def gsetToProto(gset: GSet): rd.GSet = {
    val b = rd.GSet.newBuilder()
    // using java collections and sorting for performance (avoid conversions)
    val stringElements = new ArrayList[String]
    val intElements = new ArrayList[Integer]
    val longElements = new ArrayList[jl.Long]
    val otherElements = new ArrayList[dm.OtherMessage]
    gset.elements.foreach {
      case s: String ⇒ stringElements.add(s)
      case i: Int    ⇒ intElements.add(i)
      case l: Long   ⇒ longElements.add(l)
      case other     ⇒ otherElements.add(otherMessageToProto(other))
    }
    if (!stringElements.isEmpty) {
      Collections.sort(stringElements)
      b.addAllStringElements(stringElements)
    }
    if (!intElements.isEmpty) {
      Collections.sort(intElements)
      b.addAllIntElements(intElements)
    }
    if (!longElements.isEmpty) {
      Collections.sort(longElements)
      b.addAllLongElements(longElements)
    }
    if (!otherElements.isEmpty) {
      Collections.sort(otherElements, OtherMessageComparator)
      b.addAllOtherElements(otherElements)
    }
    b.build()
  }

  def gsetFromBinary(bytes: Array[Byte]): GSet =
    gsetFromProto(rd.GSet.parseFrom(bytes))

  def gsetFromProto(gset: rd.GSet): GSet =
    GSet(gset.getStringElementsList.iterator.asScala.toSet ++
      gset.getIntElementsList.iterator.asScala ++
      gset.getLongElementsList.iterator.asScala ++
      gset.getOtherElementsList.iterator.asScala.map(otherMessageFromProto))

  private val orsetStringEntryComparator = new Comparator[rd.ORSet.StringEntry] {
    override def compare(a: rd.ORSet.StringEntry, b: rd.ORSet.StringEntry): Int =
      a.getElement.compareTo(b.getElement)
  }

  private val orsetIntEntryComparator = new Comparator[rd.ORSet.IntEntry] {
    override def compare(a: rd.ORSet.IntEntry, b: rd.ORSet.IntEntry): Int =
      a.getElement.compareTo(b.getElement)
  }

  private val orsetLongEntryComparator = new Comparator[rd.ORSet.LongEntry] {
    override def compare(a: rd.ORSet.LongEntry, b: rd.ORSet.LongEntry): Int =
      a.getElement.compareTo(b.getElement)
  }

  private val orsetOtherEntryComparator = new Comparator[rd.ORSet.OtherEntry] {
    override def compare(a: rd.ORSet.OtherEntry, b: rd.ORSet.OtherEntry): Int =
      OtherMessageComparator.compare(a.getElement, b.getElement)
  }

  def orsetToProto(orset: ORSet): rd.ORSet = {
    val b = rd.ORSet.newBuilder().setVclock(vectorClockToProto(orset.vclock))
    // using java collections and sorting for performance (avoid conversions)
    val stringElements = new ArrayList[rd.ORSet.StringEntry]
    val intElements = new ArrayList[rd.ORSet.IntEntry]
    val longElements = new ArrayList[rd.ORSet.LongEntry]
    val otherElements = new ArrayList[rd.ORSet.OtherEntry]
    orset.elements.foreach {
      case (s: String, dot) ⇒
        stringElements.add(rd.ORSet.StringEntry.newBuilder().setElement(s).setDot(vectorClockToProto(dot)).build())
      case (i: Int, dot) ⇒
        intElements.add(rd.ORSet.IntEntry.newBuilder().setElement(i).setDot(vectorClockToProto(dot)).build())
      case (l: Long, dot) ⇒
        longElements.add(rd.ORSet.LongEntry.newBuilder().setElement(l).setDot(vectorClockToProto(dot)).build())
      case (other, dot) ⇒
        otherElements.add(rd.ORSet.OtherEntry.newBuilder().setElement(otherMessageToProto(other)).
          setDot(vectorClockToProto(dot)).build())
    }
    if (!stringElements.isEmpty) {
      Collections.sort(stringElements, orsetStringEntryComparator)
      b.addAllStringElements(stringElements)
    }
    if (!intElements.isEmpty) {
      Collections.sort(intElements, orsetIntEntryComparator)
      b.addAllIntElements(intElements)
    }
    if (!longElements.isEmpty) {
      Collections.sort(longElements, orsetLongEntryComparator)
      b.addAllLongElements(longElements)
    }
    if (!otherElements.isEmpty) {
      Collections.sort(otherElements, orsetOtherEntryComparator)
      b.addAllOtherElements(otherElements)
    }
    b.build()
  }

  def orsetFromBinary(bytes: Array[Byte]): ORSet =
    orsetFromProto(rd.ORSet.parseFrom(bytes))

  def orsetFromProto(orset: rd.ORSet): ORSet = {
    val entries =
      orset.getStringElementsList.iterator.asScala.map(e ⇒ e.getElement -> vectorClockFromProto(e.getDot)) ++
        orset.getIntElementsList.iterator.asScala.map(e ⇒ e.getElement -> vectorClockFromProto(e.getDot)) ++
        orset.getLongElementsList.iterator.asScala.map(e ⇒ e.getElement -> vectorClockFromProto(e.getDot)) ++
        orset.getOtherElementsList.iterator.asScala.map(e ⇒
          otherMessageFromProto(e.getElement) -> vectorClockFromProto(e.getDot))
    ORSet(elements = entries.toMap, vclock = vectorClockFromProto(orset.getVclock))
  }

  def flagToProto(flag: Flag): rd.Flag =
    rd.Flag.newBuilder().setEnabled(flag.enabled).build()

  def flagFromBinary(bytes: Array[Byte]): Flag =
    flagFromProto(rd.Flag.parseFrom(bytes))

  def flagFromProto(flag: rd.Flag): Flag =
    Flag(flag.getEnabled)

  def lwwRegisterToProto(lwwRegister: LWWRegister): rd.LWWRegister =
    rd.LWWRegister.newBuilder().
      setTimestamp(lwwRegister.timestamp).
      setNode(uniqueAddressToProto(lwwRegister.node)).
      setState(otherMessageToProto(lwwRegister.value)).
      build()

  def lwwRegisterFromBinary(bytes: Array[Byte]): LWWRegister =
    lwwRegisterFromProto(rd.LWWRegister.parseFrom(bytes))

  def lwwRegisterFromProto(lwwRegister: rd.LWWRegister): LWWRegister =
    LWWRegister(
      uniqueAddressFromProto(lwwRegister.getNode),
      otherMessageFromProto(lwwRegister.getState),
      lwwRegister.getTimestamp,
      LWWRegister.defaultClock)

  def gcounterToProto(gcounter: GCounter): rd.GCounter = {
    val b = rd.GCounter.newBuilder()
    gcounter.state.toVector.sortBy { case (address, _) ⇒ address } foreach {
      case (address, value) ⇒ b.addEntries(rd.GCounter.Entry.newBuilder().
        setNode(uniqueAddressToProto(address)).setValue(value))
    }
    b.build()
  }

  def gcounterFromBinary(bytes: Array[Byte]): GCounter =
    gcounterFromProto(rd.GCounter.parseFrom(bytes))

  def gcounterFromProto(gcounter: rd.GCounter): GCounter = {
    GCounter(state = gcounter.getEntriesList.asScala.map(entry ⇒
      uniqueAddressFromProto(entry.getNode) -> entry.getValue)(breakOut))
  }

  def pncounterToProto(pncounter: PNCounter): rd.PNCounter =
    rd.PNCounter.newBuilder().
      setIncrements(gcounterToProto(pncounter.increments)).
      setDecrements(gcounterToProto(pncounter.decrements)).
      build()

  def pncounterFromBinary(bytes: Array[Byte]): PNCounter =
    pncounterFromProto(rd.PNCounter.parseFrom(bytes))

  def pncounterFromProto(pncounter: rd.PNCounter): PNCounter = {
    PNCounter(
      increments = gcounterFromProto(pncounter.getIncrements),
      decrements = gcounterFromProto(pncounter.getDecrements))
  }

  def vectorClockToProto(vectorClock: VectorClock): rd.VectorClock = {
    val b = rd.VectorClock.newBuilder()
    vectorClock.versions.foreach {
      case (node, value) ⇒ b.addEntries(rd.VectorClock.Entry.newBuilder().
        setNode(uniqueAddressToProto(node)).setClock(value))
    }
    b.build()
  }

  def vectorClockFromBinary(bytes: Array[Byte]): VectorClock =
    vectorClockFromProto(rd.VectorClock.parseFrom(bytes))

  def vectorClockFromProto(vectorClock: rd.VectorClock): VectorClock = {
    VectorClock(versions = vectorClock.getEntriesList.asScala.map(entry ⇒
      uniqueAddressFromProto(entry.getNode) -> entry.getClock)(breakOut))
  }

  def ormapToProto(ormap: ORMap): rd.ORMap = {
    val b = rd.ORMap.newBuilder().setKeys(orsetToProto(ormap.keys))
    ormap.entries.toVector.sortBy { case (key, _) ⇒ key } foreach {
      case (key, value) ⇒ b.addEntries(rd.ORMap.Entry.newBuilder().
        setKey(key).setValue(otherMessageToProto(value)))
    }
    b.build()
  }

  def ormapFromBinary(bytes: Array[Byte]): ORMap =
    ormapFromProto(rd.ORMap.parseFrom(decompress(bytes)))

  def ormapFromProto(ormap: rd.ORMap): ORMap = {
    val entries = ormap.getEntriesList.asScala.map(entry ⇒
      entry.getKey -> otherMessageFromProto(entry.getValue).asInstanceOf[ReplicatedData]).toMap
    ORMap(
      keys = orsetFromProto(ormap.getKeys),
      entries)
  }

  def lwwmapToProto(lwwmap: LWWMap): rd.LWWMap = {
    val b = rd.LWWMap.newBuilder().setKeys(orsetToProto(lwwmap.underlying.keys))
    lwwmap.underlying.entries.toVector.sortBy { case (key, _) ⇒ key } foreach {
      case (key, value: LWWRegister) ⇒ b.addEntries(rd.LWWMap.Entry.newBuilder().
        setKey(key).setValue(lwwRegisterToProto(value)))
    }
    b.build()
  }

  def lwwmapFromBinary(bytes: Array[Byte]): LWWMap =
    lwwmapFromProto(rd.LWWMap.parseFrom(decompress(bytes)))

  def lwwmapFromProto(lwwmap: rd.LWWMap): LWWMap = {
    val entries = lwwmap.getEntriesList.asScala.map(entry ⇒
      entry.getKey -> lwwRegisterFromProto(entry.getValue)).toMap
    LWWMap(ORMap(
      keys = orsetFromProto(lwwmap.getKeys),
      entries))
  }

  def pncountermapToProto(pncountermap: PNCounterMap): rd.PNCounterMap = {
    val b = rd.PNCounterMap.newBuilder().setKeys(orsetToProto(pncountermap.underlying.keys))
    pncountermap.underlying.entries.toVector.sortBy { case (key, _) ⇒ key } foreach {
      case (key, value: PNCounter) ⇒ b.addEntries(rd.PNCounterMap.Entry.newBuilder().
        setKey(key).setValue(pncounterToProto(value)))
    }
    b.build()
  }

  def pncountermapFromBinary(bytes: Array[Byte]): PNCounterMap =
    pncountermapFromProto(rd.PNCounterMap.parseFrom(decompress(bytes)))

  def pncountermapFromProto(pncountermap: rd.PNCounterMap): PNCounterMap = {
    val entries = pncountermap.getEntriesList.asScala.map(entry ⇒
      entry.getKey -> pncounterFromProto(entry.getValue)).toMap
    PNCounterMap(ORMap(
      keys = orsetFromProto(pncountermap.getKeys),
      entries))
  }

}

object OtherMessageComparator extends Comparator[dm.OtherMessage] {
  override def compare(a: dm.OtherMessage, b: dm.OtherMessage): Int = {
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
