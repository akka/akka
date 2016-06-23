/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Address, InternalActorRef, ActorSystem, ActorRef }
import akka.util.OptionVal

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
final class NoInboundCompression(system: ActorSystem) extends InboundCompression {
  override def hitActorRef(address: Address, ref: ActorRef): Unit = ()
  override def decompressActorRef(idx: Int): OptionVal[ActorRef] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else if (idx == 0) OptionVal.Some(system.deadLetters) // special case deadLetters  
    else OptionVal.None

  override def hitClassManifest(address: Address, manifest: String): Unit = ()
  override def decompressClassManifest(idx: Int): OptionVal[String] =
    if (idx == -1) throw new IllegalArgumentException("Attemted decompression of illegal compression id: -1")
    else OptionVal.None
}

/**
 * INTERNAL API
 *
 * Literarily, no compression!
 */
object NoOutboundCompression extends OutboundCompression {
  override def allocateActorRefCompressionId(ref: ActorRef, id: Int): Unit = ()
  override def compressActorRef(ref: ActorRef): Int = -1

  override def allocateClassManifestCompressionId(manifest: String, id: Int): Unit = ()
  override def compressClassManifest(manifest: String): Int = -1
}
