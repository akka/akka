/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import java.util.function.LongFunction

import akka.actor.{ ActorRef, ActorSystem, Address }
import akka.remote.artery._
import akka.util.OptionVal
import akka.remote.artery.OutboundCompressions
import org.agrona.collections.Long2ObjectHashMap

/** INTERNAL API */
private[remote] final class OutboundCompressionsImpl(system: ActorSystem, remoteAddress: Address) extends OutboundCompressions {

  private val actorRefsOut = new OutboundActorRefCompression(system, remoteAddress)
  private val classManifestsOut = new OutboundCompressionTable[String](system, remoteAddress)

  // actor ref compression --- 

  override def compressActorRef(ref: ActorRef): Int = actorRefsOut.compress(ref)
  override def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit =
    actorRefsOut.flipTable(table)

  // class manifest compression --- 

  override def compressClassManifest(manifest: String): Int = classManifestsOut.compress(manifest)
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
  inboundContext: InboundContext
) extends InboundCompressions {

  private val settings = CompressionSettings(system)
  private val localAddress = inboundContext.localAddress

  // FIXME we also must remove the ones that won't be used anymore - when quarantine triggers
  private[this] val _actorRefsIn = new Long2ObjectHashMap[InboundActorRefCompression]()
  private val createInboundActorRefsForOrigin = new LongFunction[InboundActorRefCompression] {
    override def apply(originUid: Long): InboundActorRefCompression = {
      val actorRefHitters = new TopHeavyHitters[ActorRef](settings.actorRefs.max)
      new InboundActorRefCompression(system, settings, originUid, inboundContext, actorRefHitters)
    }
  }
  private def actorRefsIn(originUid: Long): InboundActorRefCompression =
    _actorRefsIn.computeIfAbsent(originUid, createInboundActorRefsForOrigin)

  private[this] val _classManifestsIn = new Long2ObjectHashMap[InboundManifestCompression]()
  private val createInboundManifestsForOrigin = new LongFunction[InboundManifestCompression] {
    override def apply(originUid: Long): InboundManifestCompression = {
      val manifestHitters = new TopHeavyHitters[String](settings.manifests.max)
      new InboundManifestCompression(system, settings, originUid, inboundContext, manifestHitters)
    }
  }
  private def classManifestsIn(originUid: Long): InboundManifestCompression =
    _classManifestsIn.computeIfAbsent(originUid, createInboundManifestsForOrigin)

  // actor ref compression --- 

  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    actorRefsIn(originUid).decompress(tableVersion, idx)
  override def hitActorRef(originUid: Long, tableVersion: Int, address: Address, ref: ActorRef): Unit = {
    actorRefsIn(originUid).increment(address, ref, 1L)
  }

  // class manifest compression --- 

  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] =
    classManifestsIn(originUid).decompress(tableVersion, idx)
  override def hitClassManifest(originUid: Long, tableVersion: Int, address: Address, manifest: String): Unit = {
    classManifestsIn(originUid).increment(address, manifest, 1L)
  }
}

object NoopInboundCompressions extends InboundCompressions {
  override def hitActorRef(originUid: Long, tableVersion: Int, remote: Address, ref: ActorRef): Unit = ()
  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] = OptionVal.None

  override def hitClassManifest(originUid: Long, tableVersion: Int, remote: Address, manifest: String): Unit = ()
  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] = OptionVal.None
}

object NoopOutboundCompressions extends OutboundCompressions {
  override def compressActorRef(ref: ActorRef): Int = -1
  override def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit = ()

  override def compressClassManifest(manifest: String): Int = -1
  override def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit = ()
}
