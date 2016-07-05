/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.function.LongFunction

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.remote.artery._
import akka.util.OptionVal
import org.agrona.collections.Long2ObjectHashMap

/** INTERNAL API */
private[remote] final class OutboundCompressionsImpl(system: ActorSystem, remoteAddress: Address) extends OutboundCompressions {

  private val actorRefsOut = new OutboundActorRefCompression(system, remoteAddress)
  private val classManifestsOut = new OutboundClassManifestCompression(system, remoteAddress)

  // actor ref compression ---

  override def compressActorRef(ref: ActorRef): Int = actorRefsOut.compress(ref)
  override def actorRefCompressionTableVersion: Int = actorRefsOut.activeCompressionTableVersion
  override def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit =
    actorRefsOut.flipTable(table)

  // class manifest compression ---

  override def compressClassManifest(manifest: String): Int = classManifestsOut.compress(manifest)
  override def classManifestCompressionTableVersion: Int = classManifestsOut.activeCompressionTableVersion
  override def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit =
    classManifestsOut.flipTable(table)
}

/**
 * INTERNAL API
 *
 * One per incoming Aeron stream, actual compression tables are kept per-originUid and created on demand.
 */
private[remote] final class InboundCompressionsImpl(
  system:         ActorSystem,
  inboundContext: InboundContext) extends InboundCompressions {

  private val settings = CompressionSettings(system)

  // FIXME we also must remove the ones that won't be used anymore - when quarantine triggers
  private[this] val _actorRefsIns = new Long2ObjectHashMap[InboundActorRefCompression]()
  private val createInboundActorRefsForOrigin = new LongFunction[InboundActorRefCompression] {
    override def apply(originUid: Long): InboundActorRefCompression = {
      val actorRefHitters = new TopHeavyHitters[ActorRef](settings.actorRefs.max)
      new InboundActorRefCompression(system, settings, originUid, inboundContext, actorRefHitters)
    }
  }
  private def actorRefsIn(originUid: Long): InboundActorRefCompression =
    _actorRefsIns.computeIfAbsent(originUid, createInboundActorRefsForOrigin)

  private[this] val _classManifestsIns = new Long2ObjectHashMap[InboundManifestCompression]()
  private val createInboundManifestsForOrigin = new LongFunction[InboundManifestCompression] {
    override def apply(originUid: Long): InboundManifestCompression = {
      val manifestHitters = new TopHeavyHitters[String](settings.manifests.max)
      new InboundManifestCompression(system, settings, originUid, inboundContext, manifestHitters)
    }
  }
  private def classManifestsIn(originUid: Long): InboundManifestCompression =
    _classManifestsIns.computeIfAbsent(originUid, createInboundManifestsForOrigin)

  // actor ref compression ---

  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    actorRefsIn(originUid).decompress(tableVersion, idx)
  override def hitActorRef(originUid: Long, tableVersion: Int, address: Address, ref: ActorRef, n: Int): Unit =
    actorRefsIn(originUid).increment(address, ref, n)
  override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit =
    actorRefsIn(originUid).confirmAdvertisement(tableVersion)

  // class manifest compression ---

  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] =
    classManifestsIn(originUid).decompress(tableVersion, idx)
  override def hitClassManifest(originUid: Long, tableVersion: Int, address: Address, manifest: String, n: Int): Unit =
    classManifestsIn(originUid).increment(address, manifest, n)
  override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit =
    actorRefsIn(originUid).confirmAdvertisement(tableVersion)

  // testing utilities ---

  /** INTERNAL API: for testing only */
  private[remote] def runNextActorRefAdvertisement() = {
    import scala.collection.JavaConverters._
    _actorRefsIns.values().asScala.foreach { inbound ⇒ inbound.runNextTableAdvertisement() }
  }

  /** INTERNAL API: for testing only */
  private[remote] def runNextClassManifestAdvertisement() = {
    import scala.collection.JavaConverters._
    _classManifestsIns.values().asScala.foreach { inbound ⇒ inbound.runNextTableAdvertisement() }
  }
}
