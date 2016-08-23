/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ ActorRef, Address }
import akka.util.OptionVal

/**
 * INTERNAL API
 * Decompress and cause compression advertisements.
 *
 * One per inbound message stream thus must demux by originUid to use the right tables.
 */
private[remote] trait InboundCompressions {
  def hitActorRef(originUid: Long, tableVersion: Int, remote: Address, ref: ActorRef, n: Int): Unit
  def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef]
  def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit

  def hitClassManifest(originUid: Long, tableVersion: Int, remote: Address, manifest: String, n: Int): Unit
  def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String]
  def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit
}
/**
 * INTERNAL API
 * Compress outgoing data and handle compression advertisements to fill compression table.
 *
 * One per outgoing message stream.
 */
private[remote] trait OutboundCompressions {
  def applyActorRefCompressionTable(table: CompressionTable[ActorRef]): Unit
  def actorRefCompressionTableVersion: Int
  def compressActorRef(ref: ActorRef): Int

  def applyClassManifestCompressionTable(table: CompressionTable[String]): Unit
  def classManifestCompressionTableVersion: Int
  def compressClassManifest(manifest: String): Int
}
