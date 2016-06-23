/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ Address, ActorRef, ActorSystem }
import akka.remote.artery._
import akka.remote.artery.compress.CompressionProtocol.Events
import akka.serialization.Serialization
import akka.stream.impl.ConstantFun
import akka.util.OptionVal

/** INTERNAL API */
private[remote] final class OutboundCompressionImpl(system: ActorSystem, remoteAddress: Address) extends OutboundCompression {

  private val settings = CompressionSettings(system)

  private val actorRefsOut = new OutboundActorRefCompressionTable(system, remoteAddress)

  private val classManifestsOut = new OutboundCompressionTable[String](system, remoteAddress)

  // actor ref compression --- 

  override def allocateActorRefCompressionId(ref: ActorRef, id: Int): Unit = actorRefsOut.register(ref, id)
  override def compressActorRef(ref: ActorRef): Int = actorRefsOut.compress(ref)

  // class manifest compression --- 

  override def compressClassManifest(manifest: String): Int = classManifestsOut.compress(manifest)
  override def allocateClassManifestCompressionId(manifest: String, id: Int): Unit = classManifestsOut.register(manifest, id)
}

/** INTERNAL API */
private[remote] final class InboundCompressionImpl(
  system:         ActorSystem,
  inboundContext: InboundContext
) extends InboundCompression {

  private val settings = CompressionSettings(system)
  private val log = system.log

  private val localAddress = inboundContext.localAddress

  // TODO maybe use inbound context to get remoteAddress instead?
  val advertiseActorRef = new AdvertiseCompressionId[ActorRef] {
    override def apply(remoteAddress: Address, ref: ActorRef, id: Int): Unit = {

      log.debug(s"Advertise ActorRef compression [$ref => $id] to [$remoteAddress]")
      // TODO could use remote address via association lookup??? could be more lookups though
      inboundContext.sendControl(remoteAddress, CompressionProtocol.ActorRefCompressionAdvertisement(inboundContext.localAddress, ref, id))
    }
  }
  val advertiseManifest = new AdvertiseCompressionId[String] {
    override def apply(remoteAddress: Address, man: String, id: Int): Unit = {
      log.error(s"Advertise ClassManifest compression [$man => $id] to [$remoteAddress]")
      inboundContext.sendControl(remoteAddress, CompressionProtocol.ClassManifestCompressionAdvertisement(localAddress, man, id))
    }
  }

  private val actorRefHitters = new TopHeavyHitters[ActorRef](settings.actorRefs.max)
  private val actorRefsIn = new InboundActorRefCompressionTable(system, actorRefHitters, advertiseActorRef)

  private val manifestHitters = new TopHeavyHitters[String](settings.manifests.max)
  private val classManifestsIn = new InboundCompressionTable[String](system, manifestHitters, ConstantFun.scalaIdentityFunction, advertiseManifest)

  // actor ref compression --- 

  override def decompressActorRef(idx: Int): OptionVal[ActorRef] = {
    val value = actorRefsIn.decompress(idx)
    OptionVal.Some(value)
  }
  override def hitActorRef(address: Address, ref: ActorRef): Unit = {
    actorRefsIn.increment(address, ref, 1L)
  }

  // class manifest compression --- 

  override def decompressClassManifest(idx: Int): OptionVal[String] = {
    val value = classManifestsIn.decompress(idx)
    OptionVal.Some(value)
  }
  override def hitClassManifest(address: Address, manifest: String): Unit = {
    classManifestsIn.increment(address, manifest, 1L)
  }
}

object NoopInboundCompression extends InboundCompression {
  override def hitActorRef(remote: Address, ref: ActorRef): Unit = ()
  override def decompressActorRef(idx: Int): OptionVal[ActorRef] = OptionVal.None

  override def hitClassManifest(remote: Address, manifest: String): Unit = ()
  override def decompressClassManifest(idx: Int): OptionVal[String] = OptionVal.None
}

object NoopOutboundCompression extends OutboundCompression {
  override def compressActorRef(ref: ActorRef): Int = -1
  override def allocateActorRefCompressionId(ref: ActorRef, id: Int): Unit = ()

  override def compressClassManifest(manifest: String): Int = -1
  override def allocateClassManifestCompressionId(manifest: String, id: Int): Unit = ()
}
