/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics.protobuf

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, ObjectOutputStream }
import java.util.zip.{ GZIPInputStream, GZIPOutputStream }
import java.{ lang ⇒ jl }

import akka.actor.{ Address, ExtendedActorSystem }
import akka.cluster.metrics.protobuf.msg.{ ClusterMetricsMessages ⇒ cm }
import akka.cluster.metrics.{ ClusterMetricsMessage, ClusterMetricsSettings, EWMA, Metric, MetricsGossip, MetricsGossipEnvelope, NodeMetrics }
import akka.serialization.Serializer
import akka.util.ClassLoaderObjectInputStream
import com.google.protobuf.{ ByteString, MessageLite }

import scala.annotation.tailrec
import scala.collection.JavaConverters.{ asJavaIterableConverter, asScalaBufferConverter, setAsJavaSetConverter }

/**
 * Protobuf serializer for [[ClusterMetricsMessage]] types.
 */
class MessageSerializer(val system: ExtendedActorSystem) extends Serializer {

  private final val BufferSize = 4 * 1024

  private val fromBinaryMap = collection.immutable.HashMap[Class[_ <: ClusterMetricsMessage], Array[Byte] ⇒ AnyRef](
    classOf[MetricsGossipEnvelope] -> metricsGossipEnvelopeFromBinary)

  override val includeManifest: Boolean = true

  override val identifier = ClusterMetricsSettings(system.settings.config).SerializerIdentifier

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: MetricsGossipEnvelope ⇒
      compress(metricsGossipEnvelopeToProto(m))
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass}")
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

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = clazz match {
    case Some(c) ⇒ fromBinaryMap.get(c.asInstanceOf[Class[ClusterMetricsMessage]]) match {
      case Some(f) ⇒ f(bytes)
      case None    ⇒ throw new IllegalArgumentException(s"Unimplemented deserialization of message class $c in metrics")
    }
    case _ ⇒ throw new IllegalArgumentException("Need a metrics message class to be able to deserialize bytes in metrics")
  }

  private def addressToProto(address: Address): cm.Address.Builder = address match {
    case Address(protocol, actorSystem, Some(host), Some(port)) ⇒
      cm.Address.newBuilder().setSystem(actorSystem).setHostname(host).setPort(port).setProtocol(protocol)
    case _ ⇒ throw new IllegalArgumentException(s"Address [$address] could not be serialized: host or port missing.")
  }

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

  private def mapWithErrorMessage[T](map: Map[T, Int], value: T, unknown: String): Int = map.get(value) match {
    case Some(x) ⇒ x
    case _       ⇒ throw new IllegalArgumentException(s"Unknown $unknown [$value] in cluster message")
  }

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
          val in = new ClassLoaderObjectInputStream(system.dynamicAccess.classLoader,
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
