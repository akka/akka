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
import akka.dispatch.sysmsg.SystemMessage

/**
 * INTERNAL API
 */
private[remote] class ArterySubsystem(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {
  import provider.remoteSettings

  @volatile private[this] var address: Address = _
  @volatile private[this] var transport: Transport = _
  @volatile private[this] var tcpBinding: Option[Tcp.ServerBinding] = None
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
    address = Address("akka.artery", system.name, remoteSettings.ArteryHostname, remoteSettings.ArteryPort)
    materializer = ActorMaterializer()(system)

    transport =
      new Transport(
        address,
        system,
        materializer,
        provider,
        AkkaPduProtobufCodec)
    transport.start()
  }

  override def shutdown(): Future[Done] = {
    if (transport != null) transport.shutdown()
    else Future.successful(Done)
  }

  override def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    val cached = recipient.cachedAssociation
    val remoteAddress = recipient.path.address

    val association =
      if (cached ne null) cached
      else associate(remoteAddress)

    association.send(message, senderOption, recipient)
  }

  private def associate(remoteAddress: Address): Association = {
    val current = associations.get(remoteAddress)
    if (current ne null) current
    else {
      associations.computeIfAbsent(remoteAddress, new java.util.function.Function[Address, Association] {
        override def apply(remoteAddress: Address): Association = {
          val newAssociation = new Association(materializer, remoteAddress, transport)
          newAssociation.associate() // This is a bit costly for this blocking method :(
          newAssociation
        }
      })
    }
  }

  override def quarantine(remoteAddress: Address, uid: Option[Int]): Unit = {
    ???
  }

}

/**
 * INTERNAL API
 *
 * Thread-safe, mutable holder for association state. Main entry point for remote destined message to a specific
 * remote address.
 */
private[akka] class Association(
  val materializer: Materializer,
  val remoteAddress: Address,
  val transport: Transport) {

  @volatile private[this] var queue: SourceQueueWithComplete[Send] = _
  @volatile private[this] var systemMessageQueue: SourceQueueWithComplete[Send] = _

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit = {
    // TODO: lookup subchannel
    // FIXME: Use a different envelope than the old Send, but make sure the new is handled by deadLetters properly
    message match {
      case _: SystemMessage | _: SystemMessageDelivery.SystemMessageReply ⇒
        implicit val ec = materializer.executionContext
        systemMessageQueue.offer(Send(message, senderOption, recipient, None)).onFailure {
          case e ⇒
            // FIXME proper error handling, and quarantining
            println(s"# System message dropped, due to $e") // FIXME
        }
      case _ ⇒
        queue.offer(Send(message, senderOption, recipient, None))
    }
  }

  def quarantine(uid: Option[Int]): Unit = ()

  // Idempotent
  def associate(): Unit = {
    if (queue eq null)
      queue = Source.queue(256, OverflowStrategy.dropBuffer)
        .to(transport.outbound(remoteAddress)).run()(materializer)
    if (systemMessageQueue eq null)
      systemMessageQueue = Source.queue(256, OverflowStrategy.dropBuffer)
        .to(transport.outboundSystemMessage(remoteAddress)).run()(materializer)
  }
}

