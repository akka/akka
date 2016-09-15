/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery.compress

import akka.actor.{ ActorRef, Address }
import akka.util.OptionVal

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
case object NoInboundCompressions extends InboundCompressions {
  override def hitActorRef(originUid: Long, remote: Address, ref: ActorRef, n: Int): Unit = ()
  override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
  override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit = ()

  override def hitClassManifest(originUid: Long, remote: Address, manifest: String, n: Int): Unit = ()
  override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
  override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit = ()
}

