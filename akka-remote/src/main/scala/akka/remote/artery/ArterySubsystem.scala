/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ ActorRef, Address, ExtendedActorSystem }
import akka.event.{ Logging, LoggingAdapter }
import akka.remote.EndpointManager.Send
import akka.remote.transport.AkkaPduProtobufCodec
import akka.remote.{ DefaultMessageDispatcher, RemoteActorRef, RemoteActorRefProvider, RemoteTransport }
import akka.stream.scaladsl.{ Sink, Source, SourceQueueWithComplete, Tcp }
import akka.stream.{ ActorMaterializer, Materializer, OverflowStrategy }
import akka.{ Done, NotUsed }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

/**
 * INTERNAL API
 */
private[remote] class ArterySubsystem(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {
  @volatile private[this] var address: Address = _
  @volatile private[this] var transport: Transport = _
  @volatile private[this] var binding: Tcp.ServerBinding = _
  @volatile private[this] var materializer: Materializer = _
  override val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)

  override def defaultAddress: Address = address
  override def addresses: Set[Address] = Set(address)
  override def localAddressForRemote(remote: Address): Address = defaultAddress

  // FIXME: This does locking on putIfAbsent, we need something smarter
  private[this] val associations = new ConcurrentHashMap[Address, Association]()

  override def start(): Unit = {
    // TODO: Configure materializer properly
    // TODO: Have a supervisor actor
    address = Address("akka.artery", system.name, "localhost", provider.remoteSettings.ArteryPort)
    materializer = ActorMaterializer()(system)
    transport = new Transport(
      address,
      system,
      provider,
      AkkaPduProtobufCodec,
      new DefaultMessageDispatcher(system, provider, log))

    binding = Await.result(
      Tcp(system).bindAndHandle(transport.inboundFlow, address.host.get, address.port.get)(materializer),
      3.seconds)

    log.info("Artery started up with address {}", binding.localAddress)
  }

  override def shutdown(): Future[Done] = {
    import system.dispatcher
    binding.unbind().map(_ ⇒ Done).andThen {
      case _ ⇒ transport.killSwitch.abort(new Exception("System shut down"))
    }
  }

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    val cached = recipient.cachedAssociation
    val remoteAddress = recipient.path.address

    val association =
      if (cached ne null) cached
      else {
        val association = getAssociation(remoteAddress)
        association.associate()
        recipient.cachedAssociation = association
        association
      }

    association.send(message, senderOption, recipient)
  }

  private def getAssociation(remoteAddress: Address): Association = {
    val current = associations.get(remoteAddress)
    if (current ne null) current
    else {
      val newAssociation = new Association(materializer, remoteAddress, transport)
      val currentAssociation = associations.putIfAbsent(remoteAddress, newAssociation)
      if (currentAssociation eq null) {
        newAssociation
      } else currentAssociation
    }
  }

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = {
    getAssociation(remoteAddress).quarantine(uid)
  }

}

/**
 * INTERNAL API
 *
 * Thread-safe, mutable holder for association state. Main entry point for remote destined message to a specific
 * remote address.
 */
private[remote] class Association(
  val materializer: Materializer,
  val remoteAddress: Address,
  val transport: Transport) {
  @volatile private[this] var queue: SourceQueueWithComplete[Send] = _
  private[this] val sink: Sink[Send, Any] = transport.outbound(remoteAddress)

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    // TODO: lookup subchannel
    // FIXME: Use a different envelope than the old Send, but make sure the new is handled by deadLetters properly
    queue.offer(Send(message, senderOption, recipient, None))
  }

  def quarantine(uid: Option[Int]): Unit = ()

  // Idempotent
  def associate(): Unit = {
    queue = Source.queue(256, OverflowStrategy.dropBuffer).to(sink).run()(materializer)
  }
}

