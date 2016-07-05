/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ ActorRef, Address }
import akka.remote.artery.compress.{ CompressionTable, InboundCompressions, OutboundCompressions }
import akka.util.OptionVal

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
case object NoInboundCompressions extends InboundCompressions {
  override def hitActorRef(originUid: Long, tableVersion: Int, remote: Address, ref: ActorRef): Unit = ()
  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None

  override def hitClassManifest(originUid: Long, tableVersion: Int, remote: Address, manifest: String): Unit = ()
  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
}

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
case object NoOutboundCompressions extends OutboundCompressions {
  override def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit = ()
  override def actorRefCompressionTableVersion: Int = 0
  override def compressActorRef(ref: ActorRef): Int = -1

  override def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit = ()
  override def classManifestCompressionTableVersion: Int = 0
  override def compressClassManifest(manifest: String): Int = -1
}
