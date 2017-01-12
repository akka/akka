/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata.protobuf

import java.{ util, lang ⇒ jl }
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.TreeSet
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
import akka.protobuf.{ ByteString, GeneratedMessage }
import akka.util.ByteString.UTF_8

import scala.collection.immutable.TreeMap
import akka.cluster.UniqueAddress
import java.io.NotSerializableException
import akka.cluster.ddata.protobuf.msg.ReplicatorMessages.OtherMessage

private object ReplicatedDataSerializer {
  /*
   * Generic superclass to allow to compare Entry types used in protobuf.
   */
  abstract class KeyComparator[A <: GeneratedMessage] extends Comparator[A] {
    /**
     * Get the key from the entry. The key may be a String, Integer, Long, or Any
     * @param entry The protobuf entry used with Map types
     * @return The Key
     */
    def getKey(entry: A): Any
    final def compare(x: A, y: A): Int = compareKeys(getKey(x), getKey(y))
    private final def compareKeys(t1: Any, t2: Any): Int = (t1, t2) match {
      case (k1: String, k2: String)             ⇒ k1.compareTo(k2)
      case (k1: String, k2)                     ⇒ -1
      case (k1, k2: String)                     ⇒ 1
      case (k1: Int, k2: Int)                   ⇒ k1.compareTo(k2)
      case (k1: Int, k2)                        ⇒ -1
      case (k1, k2: Int)                        ⇒ 1
      case (k1: Long, k2: Long)                 ⇒ k1.compareTo(k2)
      case (k1: Long, k2)                       ⇒ -1
      case (k1, k2: Long)                       ⇒ 1
      case (k1: OtherMessage, k2: OtherMessage) ⇒ OtherMessageComparator.compare(k1, k2)
    }
  }

  implicit object ORMapEntryComparator extends KeyComparator[rd.ORMap.Entry] {
    override def getKey(e: rd.ORMap.Entry): Any = if (e.hasStringKey) e.getStringKey else if (e.hasIntKey) e.getIntKey else if (e.hasLongKey) e.getLongKey else e.getOtherKey
  }
  implicit object LWWMapEntryComparator extends KeyComparator[rd.LWWMap.Entry] {
    override def getKey(e: rd.LWWMap.Entry): Any = if (e.hasStringKey) e.getStringKey else if (e.hasIntKey) e.getIntKey else if (e.hasLongKey) e.getLongKey else e.getOtherKey
  }
  implicit object PNCounterMapEntryComparator extends KeyComparator[rd.PNCounterMap.Entry] {
    override def getKey(e: rd.PNCounterMap.Entry): Any = if (e.hasStringKey) e.getStringKey else if (e.hasIntKey) e.getIntKey else if (e.hasLongKey) e.getLongKey else e.getOtherKey
  }
  implicit object ORMultiMapEntryComparator extends KeyComparator[rd.ORMultiMap.Entry] {
    override def getKey(e: rd.ORMultiMap.Entry): Any = if (e.hasStringKey) e.getStringKey else if (e.hasIntKey) e.getIntKey else if (e.hasLongKey) e.getLongKey else e.getOtherKey
  }

  sealed trait ProtoMapEntryWriter[Entry <: GeneratedMessage, EntryBuilder <: GeneratedMessage.Builder[EntryBuilder], Value <: GeneratedMessage] {
    def setStringKey(builder: EntryBuilder, key: String, value: Value): Entry
    def setLongKey(builder: EntryBuilder, key: Long, value: Value): Entry
    def setIntKey(builder: EntryBuilder, key: Int, value: Value): Entry
    def setOtherKey(builder: EntryBuilder, key: dm.OtherMessage, value: Value): Entry
  }

  sealed trait ProtoMapEntryReader[Entry <: GeneratedMessage, A <: GeneratedMessage] {
    def hasStringKey(entry: Entry): Boolean
    def getStringKey(entry: Entry): String
    def hasIntKey(entry: Entry): Boolean
    def getIntKey(entry: Entry): Int
    def hasLongKey(entry: Entry): Boolean
    def getLongKey(entry: Entry): Long
    def hasOtherKey(entry: Entry): Boolean
    def getOtherKey(entry: Entry): dm.OtherMessage
    def getValue(entry: Entry): A
  }

  implicit object ORMapEntry extends ProtoMapEntryWriter[rd.ORMap.Entry, rd.ORMap.Entry.Builder, dm.OtherMessage] with ProtoMapEntryReader[rd.ORMap.Entry, dm.OtherMessage] {
    override def setStringKey(builder: rd.ORMap.Entry.Builder, key: String, value: dm.OtherMessage): rd.ORMap.Entry = builder.setStringKey(key).setValue(value).build()
    override def setLongKey(builder: rd.ORMap.Entry.Builder, key: Long, value: dm.OtherMessage): rd.ORMap.Entry = builder.setLongKey(key).setValue(value).build()
    override def setIntKey(builder: rd.ORMap.Entry.Builder, key: Int, value: dm.OtherMessage): rd.ORMap.Entry = builder.setIntKey(key).setValue(value).build()
    override def setOtherKey(builder: rd.ORMap.Entry.Builder, key: dm.OtherMessage, value: dm.OtherMessage): rd.ORMap.Entry = builder.setOtherKey(key).setValue(value).build()
    override def hasStringKey(entry: rd.ORMap.Entry): Boolean = entry.hasStringKey
    override def getStringKey(entry: rd.ORMap.Entry): String = entry.getStringKey
    override def hasIntKey(entry: rd.ORMap.Entry): Boolean = entry.hasIntKey
    override def getIntKey(entry: rd.ORMap.Entry): Int = entry.getIntKey
    override def hasLongKey(entry: rd.ORMap.Entry): Boolean = entry.hasLongKey
    override def getLongKey(entry: rd.ORMap.Entry): Long = entry.getLongKey
    override def hasOtherKey(entry: rd.ORMap.Entry): Boolean = entry.hasOtherKey
    override def getOtherKey(entry: rd.ORMap.Entry): OtherMessage = entry.getOtherKey
    override def getValue(entry: rd.ORMap.Entry): dm.OtherMessage = entry.getValue
  }

  implicit object LWWMapEntry extends ProtoMapEntryWriter[rd.LWWMap.Entry, rd.LWWMap.Entry.Builder, rd.LWWRegister] with ProtoMapEntryReader[rd.LWWMap.Entry, rd.LWWRegister] {
    override def setStringKey(builder: rd.LWWMap.Entry.Builder, key: String, value: rd.LWWRegister): rd.LWWMap.Entry = builder.setStringKey(key).setValue(value).build()
    override def setLongKey(builder: rd.LWWMap.Entry.Builder, key: Long, value: rd.LWWRegister): rd.LWWMap.Entry = builder.setLongKey(key).setValue(value).build()
    override def setIntKey(builder: rd.LWWMap.Entry.Builder, key: Int, value: rd.LWWRegister): rd.LWWMap.Entry = builder.setIntKey(key).setValue(value).build()
    override def setOtherKey(builder: rd.LWWMap.Entry.Builder, key: OtherMessage, value: rd.LWWRegister): rd.LWWMap.Entry = builder.setOtherKey(key).setValue(value).build()
    override def hasStringKey(entry: rd.LWWMap.Entry): Boolean = entry.hasStringKey
    override def getStringKey(entry: rd.LWWMap.Entry): String = entry.getStringKey
    override def hasIntKey(entry: rd.LWWMap.Entry): Boolean = entry.hasIntKey
    override def getIntKey(entry: rd.LWWMap.Entry): Int = entry.getIntKey
    override def hasLongKey(entry: rd.LWWMap.Entry): Boolean = entry.hasLongKey
    override def getLongKey(entry: rd.LWWMap.Entry): Long = entry.getLongKey
    override def hasOtherKey(entry: rd.LWWMap.Entry): Boolean = entry.hasOtherKey
    override def getOtherKey(entry: rd.LWWMap.Entry): OtherMessage = entry.getOtherKey
    override def getValue(entry: rd.LWWMap.Entry): rd.LWWRegister = entry.getValue
  }

  implicit object PNCounterMapEntry extends ProtoMapEntryWriter[rd.PNCounterMap.Entry, rd.PNCounterMap.Entry.Builder, rd.PNCounter] with ProtoMapEntryReader[rd.PNCounterMap.Entry, rd.PNCounter] {
    override def setStringKey(builder: rd.PNCounterMap.Entry.Builder, key: String, value: rd.PNCounter): rd.PNCounterMap.Entry = builder.setStringKey(key).setValue(value).build()
    override def setLongKey(builder: rd.PNCounterMap.Entry.Builder, key: Long, value: rd.PNCounter): rd.PNCounterMap.Entry = builder.setLongKey(key).setValue(value).build()
    override def setIntKey(builder: rd.PNCounterMap.Entry.Builder, key: Int, value: rd.PNCounter): rd.PNCounterMap.Entry = builder.setIntKey(key).setValue(value).build()
    override def setOtherKey(builder: rd.PNCounterMap.Entry.Builder, key: OtherMessage, value: rd.PNCounter): rd.PNCounterMap.Entry = builder.setOtherKey(key).setValue(value).build()
    override def hasStringKey(entry: rd.PNCounterMap.Entry): Boolean = entry.hasStringKey
    override def getStringKey(entry: rd.PNCounterMap.Entry): String = entry.getStringKey
    override def hasIntKey(entry: rd.PNCounterMap.Entry): Boolean = entry.hasIntKey
    override def getIntKey(entry: rd.PNCounterMap.Entry): Int = entry.getIntKey
    override def hasLongKey(entry: rd.PNCounterMap.Entry): Boolean = entry.hasLongKey
    override def getLongKey(entry: rd.PNCounterMap.Entry): Long = entry.getLongKey
    override def hasOtherKey(entry: rd.PNCounterMap.Entry): Boolean = entry.hasOtherKey
    override def getOtherKey(entry: rd.PNCounterMap.Entry): OtherMessage = entry.getOtherKey
    override def getValue(entry: rd.PNCounterMap.Entry): rd.PNCounter = entry.getValue
  }

  implicit object ORMultiMapEntry extends ProtoMapEntryWriter[rd.ORMultiMap.Entry, rd.ORMultiMap.Entry.Builder, rd.ORSet] with ProtoMapEntryReader[rd.ORMultiMap.Entry, rd.ORSet] {
    override def setStringKey(builder: rd.ORMultiMap.Entry.Builder, key: String, value: rd.ORSet): rd.ORMultiMap.Entry = builder.setStringKey(key).setValue(value).build()
    override def setLongKey(builder: rd.ORMultiMap.Entry.Builder, key: Long, value: rd.ORSet): rd.ORMultiMap.Entry = builder.setLongKey(key).setValue(value).build()
    override def setIntKey(builder: rd.ORMultiMap.Entry.Builder, key: Int, value: rd.ORSet): rd.ORMultiMap.Entry = builder.setIntKey(key).setValue(value).build()
    override def setOtherKey(builder: rd.ORMultiMap.Entry.Builder, key: dm.OtherMessage, value: rd.ORSet): rd.ORMultiMap.Entry = builder.setOtherKey(key).setValue(value).build()
    override def hasStringKey(entry: rd.ORMultiMap.Entry): Boolean = entry.hasStringKey
    override def getStringKey(entry: rd.ORMultiMap.Entry): String = entry.getStringKey
    override def hasIntKey(entry: rd.ORMultiMap.Entry): Boolean = entry.hasIntKey
    override def getIntKey(entry: rd.ORMultiMap.Entry): Int = entry.getIntKey
    override def hasLongKey(entry: rd.ORMultiMap.Entry): Boolean = entry.hasLongKey
    override def getLongKey(entry: rd.ORMultiMap.Entry): Long = entry.getLongKey
    override def hasOtherKey(entry: rd.ORMultiMap.Entry): Boolean = entry.hasOtherKey
    override def getOtherKey(entry: rd.ORMultiMap.Entry): OtherMessage = entry.getOtherKey
    override def getValue(entry: rd.ORMultiMap.Entry): rd.ORSet = entry.getValue
  }

}

/**
 * Protobuf serializer of ReplicatedData.
 */
class ReplicatedDataSerializer(val system: ExtendedActorSystem)
  extends SerializerWithStringManifest with SerializationSupport with BaseSerializer {

  import ReplicatedDataSerializer._

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
    case _: ORSet[_]            ⇒ ORSetManifest
    case _: GSet[_]             ⇒ GSetManifest
    case _: GCounter            ⇒ GCounterManifest
    case _: PNCounter           ⇒ PNCounterManifest
    case _: Flag                ⇒ FlagManifest
    case _: LWWRegister[_]      ⇒ LWWRegisterManifest
    case _: ORMap[_, _]         ⇒ ORMapManifest
    case _: LWWMap[_, _]        ⇒ LWWMapManifest
    case _: PNCounterMap[_]     ⇒ PNCounterMapManifest
    case _: ORMultiMap[_, _]    ⇒ ORMultiMapManifest
    case DeletedData            ⇒ DeletedDataManifest
    case _: VersionVector       ⇒ VersionVectorManifest

    case _: ORSetKey[_]         ⇒ ORSetKeyManifest
    case _: GSetKey[_]          ⇒ GSetKeyManifest
    case _: GCounterKey         ⇒ GCounterKeyManifest
    case _: PNCounterKey        ⇒ PNCounterKeyManifest
    case _: FlagKey             ⇒ FlagKeyManifest
    case _: LWWRegisterKey[_]   ⇒ LWWRegisterKeyManifest
    case _: ORMapKey[_, _]      ⇒ ORMapKeyManifest
    case _: LWWMapKey[_, _]     ⇒ LWWMapKeyManifest
    case _: PNCounterMapKey[_]  ⇒ PNCounterMapKeyManifest
    case _: ORMultiMapKey[_, _] ⇒ ORMultiMapKeyManifest

    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: ORSet[_]         ⇒ compress(orsetToProto(m))
    case m: GSet[_]          ⇒ gsetToProto(m).toByteArray
    case m: GCounter         ⇒ gcounterToProto(m).toByteArray
    case m: PNCounter        ⇒ pncounterToProto(m).toByteArray
    case m: Flag             ⇒ flagToProto(m).toByteArray
    case m: LWWRegister[_]   ⇒ lwwRegisterToProto(m).toByteArray
    case m: ORMap[_, _]      ⇒ compress(ormapToProto(m))
    case m: LWWMap[_, _]     ⇒ compress(lwwmapToProto(m))
    case m: PNCounterMap[_]  ⇒ compress(pncountermapToProto(m))
    case m: ORMultiMap[_, _] ⇒ compress(multimapToProto(m))
    case DeletedData         ⇒ dm.Empty.getDefaultInstance.toByteArray
    case m: VersionVector    ⇒ versionVectorToProto(m).toByteArray
    case Key(id)             ⇒ keyIdToBinary(id)
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new NotSerializableException(
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

  /*
   * Convert a Map[A, B] to an Iterable[Entry] where Entry is the protobuf map entry.
   */
  private def getEntries[IKey, IValue, EntryBuilder <: GeneratedMessage.Builder[EntryBuilder], PEntry <: GeneratedMessage, PValue <: GeneratedMessage](input: Map[IKey, IValue], createBuilder: () ⇒ EntryBuilder, valueConverter: IValue ⇒ PValue)(implicit comparator: Comparator[PEntry], eh: ProtoMapEntryWriter[PEntry, EntryBuilder, PValue]): java.lang.Iterable[PEntry] = {
    // The resulting Iterable needs to be ordered deterministically in order to create same signature upon serializing same data
    val protoEntries = new TreeSet[PEntry](comparator)
    input.foreach {
      case (key: String, value) ⇒ protoEntries.add(eh.setStringKey(createBuilder(), key, valueConverter(value)))
      case (key: Int, value)    ⇒ protoEntries.add(eh.setIntKey(createBuilder(), key, valueConverter(value)))
      case (key: Long, value)   ⇒ protoEntries.add(eh.setLongKey(createBuilder(), key, valueConverter(value)))
      case (key, value)         ⇒ protoEntries.add(eh.setOtherKey(createBuilder(), otherMessageToProto(key), valueConverter(value)))
    }
    protoEntries
  }

  def ormapToProto(ormap: ORMap[_, _]): rd.ORMap = {
    val entries: jl.Iterable[rd.ORMap.Entry] = getEntries(ormap.values, rd.ORMap.Entry.newBuilder, otherMessageToProto)
    rd.ORMap.newBuilder().setKeys(orsetToProto(ormap.keys)).addAllEntries(entries).build()
  }

  def ormapFromBinary(bytes: Array[Byte]): ORMap[Any, ReplicatedData] =
    ormapFromProto(rd.ORMap.parseFrom(decompress(bytes)))

  def mapTypeFromProto[PEntry <: GeneratedMessage, A <: GeneratedMessage, B <: ReplicatedData](input: util.List[PEntry], valueCreator: A ⇒ B)(implicit eh: ProtoMapEntryReader[PEntry, A]): Map[Any, B] = {
    input.asScala.map { entry ⇒
      if (eh.hasStringKey(entry)) eh.getStringKey(entry) → valueCreator(eh.getValue(entry))
      else if (eh.hasIntKey(entry)) eh.getIntKey(entry) → valueCreator(eh.getValue(entry))
      else if (eh.hasLongKey(entry)) eh.getLongKey(entry) → valueCreator(eh.getValue(entry))
      else if (eh.hasOtherKey(entry)) otherMessageFromProto(eh.getOtherKey(entry)) → valueCreator(eh.getValue(entry))
      else throw new IllegalArgumentException(s"Can't deserialize ${entry.getClass} because it does not have any key in the serialized message.")
    }.toMap
  }

  def ormapFromProto(ormap: rd.ORMap): ORMap[Any, ReplicatedData] = {
    val entries = mapTypeFromProto(ormap.getEntriesList, (v: dm.OtherMessage) ⇒ otherMessageFromProto(v).asInstanceOf[ReplicatedData])
    new ORMap(
      keys = orsetFromProto(ormap.getKeys),
      entries)
  }

  def lwwmapToProto(lwwmap: LWWMap[_, _]): rd.LWWMap = {
    val entries: jl.Iterable[rd.LWWMap.Entry] = getEntries(lwwmap.underlying.entries, rd.LWWMap.Entry.newBuilder, lwwRegisterToProto)
    rd.LWWMap.newBuilder().setKeys(orsetToProto(lwwmap.underlying.keys)).addAllEntries(entries).build()
  }

  def lwwmapFromBinary(bytes: Array[Byte]): LWWMap[Any, Any] =
    lwwmapFromProto(rd.LWWMap.parseFrom(decompress(bytes)))

  def lwwmapFromProto(lwwmap: rd.LWWMap): LWWMap[Any, Any] = {
    val entries = mapTypeFromProto(lwwmap.getEntriesList, lwwRegisterFromProto)
    new LWWMap(new ORMap(
      keys = orsetFromProto(lwwmap.getKeys),
      entries))
  }

  def pncountermapToProto(pncountermap: PNCounterMap[_]): rd.PNCounterMap = {
    val entries: jl.Iterable[rd.PNCounterMap.Entry] = getEntries(pncountermap.underlying.entries, rd.PNCounterMap.Entry.newBuilder, pncounterToProto)
    rd.PNCounterMap.newBuilder().setKeys(orsetToProto(pncountermap.underlying.keys)).addAllEntries(entries).build()
  }

  def pncountermapFromBinary(bytes: Array[Byte]): PNCounterMap[_] =
    pncountermapFromProto(rd.PNCounterMap.parseFrom(decompress(bytes)))

  def pncountermapFromProto(pncountermap: rd.PNCounterMap): PNCounterMap[_] = {
    val entries = mapTypeFromProto(pncountermap.getEntriesList, pncounterFromProto)
    new PNCounterMap(new ORMap(
      keys = orsetFromProto(pncountermap.getKeys),
      entries))
  }

  def multimapToProto(multimap: ORMultiMap[_, _]): rd.ORMultiMap = {
    val entries: jl.Iterable[rd.ORMultiMap.Entry] = getEntries(multimap.underlying.entries, rd.ORMultiMap.Entry.newBuilder, orsetToProto)
    rd.ORMultiMap.newBuilder().setKeys(orsetToProto(multimap.underlying.keys)).addAllEntries(entries).build()
  }

  def multimapFromBinary(bytes: Array[Byte]): ORMultiMap[Any, Any] =
    multimapFromProto(rd.ORMultiMap.parseFrom(decompress(bytes)))

  def multimapFromProto(multimap: rd.ORMultiMap): ORMultiMap[Any, Any] = {
    val entries = mapTypeFromProto(multimap.getEntriesList, orsetFromProto)
    new ORMultiMap(new ORMap(
      keys = orsetFromProto(multimap.getKeys),
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
