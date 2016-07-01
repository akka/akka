/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ ActorRef, ActorSystem, Address, InternalActorRef }
import akka.remote.artery.compress.CompressionTable
import akka.util.OptionVal

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
final class NoInboundCompressions(system: ActorSystem) extends InboundCompressions {
  override def hitActorRef(originUid: Long, tableVersion: Int, remote: Address, ref: ActorRef): Unit = ()
  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else if (idx == 0) OptionVal.Some(system.deadLetters) // special case deadLetters  
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
object NoOutboundCompressions extends OutboundCompressions {
  override def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit = ()
  override def compressActorRef(ref: ActorRef): Int = -1

  override def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit = ()
  override def compressClassManifest(manifest: String): Int = -1
}
