/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Future

import akka.Done
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.remote.RemoteActorRef
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransport
import akka.remote.Remoting
import akka.remote.artery.aeron.ArteryAeronUdpTransport
import akka.remote.artery.tcp.ArteryTcpTransport
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[akka] class HybridTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider)
  extends RemoteTransport(_system, _provider) {

  private val classicTransport = new Remoting(system, provider)
  private val arteryTransport = provider.remoteSettings.Artery.Transport match {
    case ArterySettings.AeronUpd ⇒ new ArteryAeronUdpTransport(system, provider)
    case ArterySettings.Tcp      ⇒ new ArteryTcpTransport(system, provider, tlsEnabled = false)
    case ArterySettings.TlsTcp   ⇒ new ArteryTcpTransport(system, provider, tlsEnabled = true)
  }

  override val log: LoggingAdapter = Logging(system, getClass.getName)

  def isArteryProtocol(address: Address): Boolean =
    address.protocol == ArteryTransport.ProtocolName

  private val arteryPortOffset = system.settings.config.getInt("akka.remote.artery.hybrid-port-offset")

  private def correspondingOtherAddress(address: Address): Address = {
    if (arteryPortOffset == 0)
      address
    else if (isArteryProtocol(address))
      address.copy(protocol = classicTransport.defaultAddress.protocol, port = Some(address.port.get - arteryPortOffset))
    else
      address.copy(protocol = ArteryTransport.ProtocolName, port = Some(address.port.get + arteryPortOffset))
  }

  override def addresses: Set[Address] = classicTransport.addresses.union(arteryTransport.addresses)

  override def defaultAddress: Address = {
    if (arteryTransport.settings.RollingMode == ArterySettings.RollingUpgradeArteryAsDefault) arteryTransport.defaultAddress
    else classicTransport.defaultAddress
  }

  override def localAddressForRemote(remote: Address): Address = {
    if (isArteryProtocol(remote)) arteryTransport.localAddressForRemote(remote)
    else classicTransport.localAddressForRemote(remote)
  }

  override def start(): Unit = {
    classicTransport.start()
    arteryTransport.start()
  }

  override def shutdown(): Future[Done] = {
    import system.dispatcher
    classicTransport.shutdown().recover { case _ ⇒ Done }
      .flatMap(_ ⇒ arteryTransport.shutdown())
  }

  override def send(message: Any, senderOption: OptionVal[ActorRef], recipient: RemoteActorRef): Unit = {
    if (isArteryProtocol(recipient.path.address))
      arteryTransport.send(message, senderOption, recipient)
    else
      classicTransport.send(message, senderOption, recipient)
  }

  override def quarantine(address: Address, uid: Option[Long], reason: String): Unit = {
    if (isArteryProtocol(address)) {
      arteryTransport.quarantine(address, uid, reason)
      if (arteryPortOffset != 0)
        classicTransport.quarantine(correspondingOtherAddress(address), uid, reason)
    } else {
      classicTransport.quarantine(address, uid, reason)
      if (arteryPortOffset != 0)
        arteryTransport.quarantine(correspondingOtherAddress(address), uid, reason)
    }
  }
}
