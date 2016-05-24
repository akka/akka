/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata.protobuf

import java.{ lang ⇒ jl }
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.breakOut
import akka.actor.ExtendedActorSystem
import akka.cluster.ddata._
import akka.cluster.ddata.Replicator.Internal._
import akka.cluster.ddata.protobuf.msg.{ ReplicatedDataMessages ⇒ rd }
import akka.cluster.ddata.protobuf.msg.{ ReplicatorMessages ⇒ dm }
import akka.serialization.SerializerWithStringManifest
import akka.serialization.BaseSerializer
import akka.protobuf.ByteString
import akka.util.ByteString.UTF_8
import scala.collection.immutable.TreeMap
import akka.cluster.UniqueAddress

/**
 * Protobuf serializer of ReplicatedData.
 */
class ReplicatedDataSerializer(val system: ExtendedActorSystem)
  extends SerializerWithStringManifest with SerializationSupport with BaseSerializer {

  private val DeletedDataManifest = "A"
  private val GSetManifest = "B"
  private val GSetKeyManifest = "b"
  private val ORSetManifest = "C"
  private val ORSetKeyManifest = "c"
  private val FlagManifest = "D"
  private val FlagKeyManifest = "d"
  private val LWWRegisterManifest = "E"
  private val LWWRegisterKeyManifest = "e"
  private val GCounterManifest = "F"
  private val GCounterKeyManifest = "f"
  private val PNCounterManifest = "G"
  private val PNCounterKeyManifest = "g"
  private val ORMapManifest = "H"
  private val ORMapKeyManifest = "h"
  private val LWWMapManifest = "I"
  private val LWWMapKeyManifest = "i"
  private val PNCounterMapManifest = "J"
  private val PNCounterMapKeyManifest = "j"
  private val ORMultiMapManifest = "K"
  private val ORMultiMapKeyManifest = "k"
  private val VersionVectorManifest = "L"

  private val fromBinaryMap = collection.immutable.HashMap[String, Array[Byte] ⇒ AnyRef](
    GSetManifest → gsetFromBinary,
    ORSetManifest → orsetFromBinary,
    FlagManifest → flagFromBinary,
    LWWRegisterManifest → lwwRegisterFromBinary,
    GCounterManifest → gcounterFromBinary,
    PNCounterManifest → pncounterFromBinary,
    ORMapManifest → ormapFromBinary,
    LWWMapManifest → lwwmapFromBinary,
    PNCounterMapManifest → pncountermapFromBinary,
    ORMultiMapManifest → multimapFromBinary,
    DeletedDataManifest → (_ ⇒ DeletedData),
    VersionVectorManifest → versionVectorFromBinary,

    GSetKeyManifest → (bytes ⇒ GSetKey(keyIdFromBinary(bytes))),
    ORSetKeyManifest → (bytes ⇒ ORSetKey(keyIdFromBinary(bytes))),
    FlagKeyManifest → (bytes ⇒ FlagKey(keyIdFromBinary(bytes))),
    LWWRegisterKeyManifest → (bytes ⇒ LWWRegisterKey(keyIdFromBinary(bytes))),
    GCounterKeyManifest → (bytes ⇒ GCounterKey(keyIdFromBinary(bytes))),
    PNCounterKeyManifest → (bytes ⇒ PNCounterKey(keyIdFromBinary(bytes))),
    ORMapKeyManifest → (bytes ⇒ ORMapKey(keyIdFromBinary(bytes))),
    LWWMapKeyManifest → (bytes ⇒ LWWMapKey(keyIdFromBinary(bytes))),
    PNCounterMapKeyManifest → (bytes ⇒ PNCounterMapKey(keyIdFromBinary(bytes))),
    ORMultiMapKeyManifest → (bytes ⇒ ORMultiMapKey(keyIdFromBinary(bytes))))

  override def manifest(obj: AnyRef): String = obj match {
    case _: ORSet[_]          ⇒ ORSetManifest
    case _: GSet[_]           ⇒ GSetManifest
    case _: GCounter          ⇒ GCounterManifest
    case _: PNCounter         ⇒ PNCounterManifest
    case _: Flag              ⇒ FlagManifest
    case _: LWWRegister[_]    ⇒ LWWRegisterManifest
    case _: ORMap[_]          ⇒ ORMapManifest
    case _: LWWMap[_]         ⇒ LWWMapManifest
    case _: PNCounterMap      ⇒ PNCounterMapManifest
    case _: ORMultiMap[_]     ⇒ ORMultiMapManifest
    case DeletedData          ⇒ DeletedDataManifest
    case _: VersionVector     ⇒ VersionVectorManifest

    case _: ORSetKey[_]       ⇒ ORSetKeyManifest
    case _: GSetKey[_]        ⇒ GSetKeyManifest
    case _: GCounterKey       ⇒ GCounterKeyManifest
    case _: PNCounterKey      ⇒ PNCounterKeyManifest
    case _: FlagKey           ⇒ FlagKeyManifest
    case _: LWWRegisterKey[_] ⇒ LWWRegisterKeyManifest
    case _: ORMapKey[_]       ⇒ ORMapKeyManifest
    case _: LWWMapKey[_]      ⇒ LWWMapKeyManifest
    case _: PNCounterMapKey   ⇒ PNCounterMapKeyManifest
    case _: ORMultiMapKey[_]  ⇒ ORMultiMapKeyManifest

    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: ORSet[_]       ⇒ compress(orsetToProto(m))
    case m: GSet[_]        ⇒ gsetToProto(m).toByteArray
    case m: GCounter       ⇒ gcounterToProto(m).toByteArray
    case m: PNCounter      ⇒ pncounterToProto(m).toByteArray
    case m: Flag           ⇒ flagToProto(m).toByteArray
    case m: LWWRegister[_] ⇒ lwwRegisterToProto(m).toByteArray
    case m: ORMap[_]       ⇒ compress(ormapToProto(m))
    case m: LWWMap[_]      ⇒ compress(lwwmapToProto(m))
    case m: PNCounterMap   ⇒ compress(pncountermapToProto(m))
    case m: ORMultiMap[_]  ⇒ compress(multimapToProto(m))
    case DeletedData       ⇒ dm.Empty.getDefaultInstance.toByteArray
    case m: VersionVector  ⇒ versionVectorToProto(m).toByteArray
    case Key(id)           ⇒ keyIdToBinary(id)
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new IllegalArgumentException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  def gsetToProto(gset: GSet[_]): rd.GSet = {
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

  def gsetFromBinary(bytes: Array[Byte]): GSet[_] =
    gsetFromProto(rd.GSet.parseFrom(bytes))

  def gsetFromProto(gset: rd.GSet): GSet[Any] =
    GSet(gset.getStringElementsList.iterator.asScala.toSet ++
      gset.getIntElementsList.iterator.asScala ++
      gset.getLongElementsList.iterator.asScala ++
      gset.getOtherElementsList.iterator.asScala.map(otherMessageFromProto))

  def orsetToProto(orset: ORSet[_]): rd.ORSet =
    orsetToProtoImpl(orset.asInstanceOf[ORSet[Any]])

  private def orsetToProtoImpl(orset: ORSet[Any]): rd.ORSet = {
    val b = rd.ORSet.newBuilder().setVvector(versionVectorToProto(orset.vvector))
    // using java collections and sorting for performance (avoid conversions)
    val stringElements = new ArrayList[String]
    val intElements = new ArrayList[Integer]
    val longElements = new ArrayList[jl.Long]
    val otherElements = new ArrayList[dm.OtherMessage]
    var otherElementsMap = Map.empty[dm.OtherMessage, Any]
    orset.elementsMap.keysIterator.foreach {
      case s: String ⇒ stringElements.add(s)
      case i: Int    ⇒ intElements.add(i)
      case l: Long   ⇒ longElements.add(l)
      case other ⇒
        val enclosedMsg = otherMessageToProto(other)
        otherElements.add(enclosedMsg)
        // need the mapping back to the `other` when adding dots
        otherElementsMap = otherElementsMap.updated(enclosedMsg, other)
    }

    def addDots(elements: ArrayList[_]): Unit = {
      // add corresponding dots in same order
      val iter = elements.iterator
      while (iter.hasNext) {
        val element = iter.next() match {
          case enclosedMsg: dm.OtherMessage ⇒ otherElementsMap(enclosedMsg)
          case e                            ⇒ e
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
      Collections.sort(otherElements, OtherMessageComparator)
      b.addAllOtherElements(otherElements)
      addDots(otherElements)
    }

    b.build()
  }

  def orsetFromBinary(bytes: Array[Byte]): ORSet[Any] =
    orsetFromProto(rd.ORSet.parseFrom(decompress(bytes)))

  def orsetFromProto(orset: rd.ORSet): ORSet[Any] = {
    val elements: Iterator[Any] =
      (orset.getStringElementsList.iterator.asScala ++
        orset.getIntElementsList.iterator.asScala ++
        orset.getLongElementsList.iterator.asScala ++
        orset.getOtherElementsList.iterator.asScala.map(otherMessageFromProto))

    val dots = orset.getDotsList.asScala.map(versionVectorFromProto).iterator
    val elementsMap = elements.zip(dots).toMap

    new ORSet(elementsMap, vvector = versionVectorFromProto(orset.getVvector))
  }

  def flagToProto(flag: Flag): rd.Flag =
    rd.Flag.newBuilder().setEnabled(flag.enabled).build()

  def flagFromBinary(bytes: Array[Byte]): Flag =
    flagFromProto(rd.Flag.parseFrom(bytes))

  def flagFromProto(flag: rd.Flag): Flag =
    Flag(flag.getEnabled)

  def lwwRegisterToProto(lwwRegister: LWWRegister[_]): rd.LWWRegister =
    rd.LWWRegister.newBuilder().
      setTimestamp(lwwRegister.timestamp).
      setNode(uniqueAddressToProto(lwwRegister.node)).
      setState(otherMessageToProto(lwwRegister.value)).
      build()

  def lwwRegisterFromBinary(bytes: Array[Byte]): LWWRegister[Any] =
    lwwRegisterFromProto(rd.LWWRegister.parseFrom(bytes))

  def lwwRegisterFromProto(lwwRegister: rd.LWWRegister): LWWRegister[Any] =
    new LWWRegister(
      uniqueAddressFromProto(lwwRegister.getNode),
      otherMessageFromProto(lwwRegister.getState),
      lwwRegister.getTimestamp)

  def gcounterToProto(gcounter: GCounter): rd.GCounter = {
    val b = rd.GCounter.newBuilder()
    gcounter.state.toVector.sortBy { case (address, _) ⇒ address }.foreach {
      case (address, value) ⇒ b.addEntries(rd.GCounter.Entry.newBuilder().
        setNode(uniqueAddressToProto(address)).setValue(ByteString.copyFrom(value.toByteArray)))
    }
    b.build()
  }

  def gcounterFromBinary(bytes: Array[Byte]): GCounter =
    gcounterFromProto(rd.GCounter.parseFrom(bytes))

  def gcounterFromProto(gcounter: rd.GCounter): GCounter = {
    new GCounter(state = gcounter.getEntriesList.asScala.map(entry ⇒
      uniqueAddressFromProto(entry.getNode) → BigInt(entry.getValue.toByteArray))(breakOut))
  }

  def pncounterToProto(pncounter: PNCounter): rd.PNCounter =
    rd.PNCounter.newBuilder().
      setIncrements(gcounterToProto(pncounter.increments)).
      setDecrements(gcounterToProto(pncounter.decrements)).
      build()

  def pncounterFromBinary(bytes: Array[Byte]): PNCounter =
    pncounterFromProto(rd.PNCounter.parseFrom(bytes))

  def pncounterFromProto(pncounter: rd.PNCounter): PNCounter = {
    new PNCounter(
      increments = gcounterFromProto(pncounter.getIncrements),
      decrements = gcounterFromProto(pncounter.getDecrements))
  }

  def versionVectorToProto(versionVector: VersionVector): rd.VersionVector = {
    val b = rd.VersionVector.newBuilder()
    versionVector.versionsIterator.foreach {
      case (node, value) ⇒ b.addEntries(rd.VersionVector.Entry.newBuilder().
        setNode(uniqueAddressToProto(node)).setVersion(value))
    }
    b.build()
  }

  def versionVectorFromBinary(bytes: Array[Byte]): VersionVector =
    versionVectorFromProto(rd.VersionVector.parseFrom(bytes))

  def versionVectorFromProto(versionVector: rd.VersionVector): VersionVector = {
    val entries = versionVector.getEntriesList
    if (entries.isEmpty)
      VersionVector.empty
    else if (entries.size == 1)
      VersionVector(uniqueAddressFromProto(entries.get(0).getNode), entries.get(0).getVersion)
    else {
      val versions: TreeMap[UniqueAddress, Long] = versionVector.getEntriesList.asScala.map(entry ⇒
        uniqueAddressFromProto(entry.getNode) → entry.getVersion)(breakOut)
      VersionVector(versions)
    }
  }

  def ormapToProto(ormap: ORMap[_]): rd.ORMap = {
    val b = rd.ORMap.newBuilder().setKeys(orsetToProto(ormap.keys))
    ormap.entries.toVector.sortBy { case (key, _) ⇒ key }.foreach {
      case (key, value) ⇒ b.addEntries(rd.ORMap.Entry.newBuilder().
        setKey(key).setValue(otherMessageToProto(value)))
    }
    b.build()
  }

  def ormapFromBinary(bytes: Array[Byte]): ORMap[ReplicatedData] =
    ormapFromProto(rd.ORMap.parseFrom(decompress(bytes)))

  def ormapFromProto(ormap: rd.ORMap): ORMap[ReplicatedData] = {
    val entries = ormap.getEntriesList.asScala.map(entry ⇒
      entry.getKey → otherMessageFromProto(entry.getValue).asInstanceOf[ReplicatedData]).toMap
    new ORMap(
      keys = orsetFromProto(ormap.getKeys).asInstanceOf[ORSet[String]],
      entries)
  }

  def lwwmapToProto(lwwmap: LWWMap[_]): rd.LWWMap = {
    val b = rd.LWWMap.newBuilder().setKeys(orsetToProto(lwwmap.underlying.keys))
    lwwmap.underlying.entries.toVector.sortBy { case (key, _) ⇒ key }.foreach {
      case (key, value) ⇒ b.addEntries(rd.LWWMap.Entry.newBuilder().
        setKey(key).setValue(lwwRegisterToProto(value)))
    }
    b.build()
  }

  def lwwmapFromBinary(bytes: Array[Byte]): LWWMap[Any] =
    lwwmapFromProto(rd.LWWMap.parseFrom(decompress(bytes)))

  def lwwmapFromProto(lwwmap: rd.LWWMap): LWWMap[Any] = {
    val entries = lwwmap.getEntriesList.asScala.map(entry ⇒
      entry.getKey → lwwRegisterFromProto(entry.getValue)).toMap
    new LWWMap(new ORMap(
      keys = orsetFromProto(lwwmap.getKeys).asInstanceOf[ORSet[String]],
      entries))
  }

  def pncountermapToProto(pncountermap: PNCounterMap): rd.PNCounterMap = {
    val b = rd.PNCounterMap.newBuilder().setKeys(orsetToProto(pncountermap.underlying.keys))
    pncountermap.underlying.entries.toVector.sortBy { case (key, _) ⇒ key }.foreach {
      case (key, value: PNCounter) ⇒ b.addEntries(rd.PNCounterMap.Entry.newBuilder().
        setKey(key).setValue(pncounterToProto(value)))
    }
    b.build()
  }

  def pncountermapFromBinary(bytes: Array[Byte]): PNCounterMap =
    pncountermapFromProto(rd.PNCounterMap.parseFrom(decompress(bytes)))

  def pncountermapFromProto(pncountermap: rd.PNCounterMap): PNCounterMap = {
    val entries = pncountermap.getEntriesList.asScala.map(entry ⇒
      entry.getKey → pncounterFromProto(entry.getValue)).toMap
    new PNCounterMap(new ORMap(
      keys = orsetFromProto(pncountermap.getKeys).asInstanceOf[ORSet[String]],
      entries))
  }

  def multimapToProto(multimap: ORMultiMap[_]): rd.ORMultiMap = {
    val b = rd.ORMultiMap.newBuilder().setKeys(orsetToProto(multimap.underlying.keys))
    multimap.underlying.entries.toVector.sortBy { case (key, _) ⇒ key }.foreach {
      case (key, value) ⇒ b.addEntries(rd.ORMultiMap.Entry.newBuilder().
        setKey(key).setValue(orsetToProto(value)))
    }
    b.build()
  }

  def multimapFromBinary(bytes: Array[Byte]): ORMultiMap[Any] =
    multimapFromProto(rd.ORMultiMap.parseFrom(decompress(bytes)))

  def multimapFromProto(multimap: rd.ORMultiMap): ORMultiMap[Any] = {
    val entries = multimap.getEntriesList.asScala.map(entry ⇒
      entry.getKey → orsetFromProto(entry.getValue)).toMap
    new ORMultiMap(new ORMap(
      keys = orsetFromProto(multimap.getKeys).asInstanceOf[ORSet[String]],
      entries))
  }

  def keyIdToBinary(id: String): Array[Byte] =
    id.getBytes(UTF_8)

  def keyIdFromBinary(bytes: Array[Byte]): String =
    new String(bytes, UTF_8)

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
