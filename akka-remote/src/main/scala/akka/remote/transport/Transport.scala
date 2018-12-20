/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport

import scala.concurrent.{ Future, Promise }
import scala.util.control.NoStackTrace

import akka.actor.{ ActorRef, Address, NoSerializationVerificationNeeded }
import akka.util.{ ByteString, unused }
import akka.remote.transport.AssociationHandle.HandleEventListener
import akka.AkkaException
import akka.actor.DeadLetterSuppression
import akka.event.LoggingAdapter

object Transport {

  trait AssociationEvent extends NoSerializationVerificationNeeded

  /**
   * Indicates that the association setup request is invalid, and it is impossible to recover (malformed IP address,
   * hostname, etc.).
   */
  @SerialVersionUID(1L)
  final case class InvalidAssociationException(msg: String, cause: Throwable = null) extends AkkaException(msg, cause) with NoStackTrace

  /**
   * Message sent to a [[akka.remote.transport.Transport.AssociationEventListener]] registered to a transport
   * (via the Promise returned by [[akka.remote.transport.Transport#listen]]) when an inbound association request arrives.
   *
   * @param association
   *   The handle for the inbound association.
   */
  final case class InboundAssociation(association: AssociationHandle) extends AssociationEvent

  /**
   * An interface that needs to be implemented by the user of a transport to listen to association events
   */
  trait AssociationEventListener {

    /**
     * Called by the transport to notify the listener about an AssociationEvent
     * @param ev The AssociationEvent of the transport
     */
    def notify(ev: AssociationEvent): Unit
  }

  /**
   * Class to convert ordinary [[akka.actor.ActorRef]] instances to an AssociationEventListener. The adapter will
   * forward event objects as messages to the provided ActorRef.
   * @param actor
   */
  final case class ActorAssociationEventListener(actor: ActorRef) extends AssociationEventListener {
    override def notify(ev: AssociationEvent): Unit = actor ! ev
  }

}

/**
 * An SPI layer for implementing asynchronous transport mechanisms. The Transport is responsible for initializing the
 * underlying transmission mechanism and setting up logical links between transport entities.
 *
 * Transport implementations that are loaded dynamically by the remoting must have a constructor that accepts a
 * [[com.typesafe.config.Config]] and an [[akka.actor.ExtendedActorSystem]] as parameters.
 */
trait Transport {
  import akka.remote.transport.Transport._

  /**
   * Returns a string that will be used as the scheme part of the URLs corresponding to this transport
   * @return the scheme string
   */
  def schemeIdentifier: String

  /**
   * A function that decides whether the specific transport instance is responsible for delivering
   * to a given address. The function must be thread-safe and non-blocking.
   *
   * The purpose of this function is to resolve cases when the scheme part of an URL is not enough to resolve
   * the correct transport i.e. multiple instances of the same transport implementation are loaded. These cases arise when
   *  - the same transport, but with different configurations is used for different remote systems
   *  - a transport is able to serve one address only (hardware protocols, e.g. Serial port) and multiple
   *  instances are needed to be loaded for different endpoints.
   *
   * @return whether the transport instance is responsible to serve communications to the given address.
   */
  def isResponsibleFor(address: Address): Boolean

  /**
   * Defines the maximum size of payload this transport is able to deliver. All transports MUST support at least
   * 32kBytes (32000 octets) of payload, but some MAY support larger sizes.
   * @return
   */
  def maximumPayloadBytes: Int

  /**
   * Asynchronously attempts to setup the transport layer to listen and accept incoming associations. The result of the
   * attempt is wrapped by a Future returned by this method. The pair contained in the future contains a Promise for an
   * ActorRef. By completing this Promise with an [[akka.remote.transport.Transport.AssociationEventListener]], that
   * listener becomes responsible for handling incoming associations. Until the Promise is not completed, no associations
   * are processed.
   *
   * @return
   *   A Future containing a pair of the bound local address and a Promise of an AssociationListener that must be
   *   completed by the consumer of the future.
   */
  def listen: Future[(Address, Promise[AssociationEventListener])]

  // Need to do like this for binary compatibility reasons
  // def boundAddress: Address

  /**
   * Asynchronously opens a logical duplex link between two Transport Entities over a network. It could be backed by a
   * real transport-layer connection (TCP), more lightweight connections provided over datagram protocols (UDP with
   * additional services), substreams of multiplexed connections (SCTP) or physical links (serial port).
   *
   * This call returns a future of an [[akka.remote.transport.AssociationHandle]]. A failed future indicates that
   * the association attempt was unsuccessful. If the exception is [[akka.remote.transport.Transport.InvalidAssociationException]]
   * then the association request was invalid, and it is impossible to recover.
   *
   * @param remoteAddress
   *   The address of the remote transport entity.
   * @return
   *   A status instance representing failure or a success containing an [[akka.remote.transport.AssociationHandle]]
   */
  def associate(remoteAddress: Address): Future[AssociationHandle]

  /**
   * Shuts down the transport layer and releases all the corresponding resources. Shutdown is asynchronous signalling
   * the end of the shutdown by completing the returned future.
   *
   * The transport SHOULD try flushing pending writes before becoming completely closed.
   * @return
   *   Future signalling the completion of shutdown
   */
  def shutdown(): Future[Boolean]

  /**
   * This method allows upper layers to send management commands to the transport. It is the responsibility of the
   * sender to send appropriate commands to different transport implementations. Unknown commands will be ignored.
   *
   * @param cmd Command message to the transport
   * @return Future that succeeds when the command was handled or dropped
   */
  def managementCommand(@unused cmd: Any): Future[Boolean] = { Future.successful(false) }

}

object AssociationHandle {

  /**
   * Trait for events that the registered listener for an [[akka.remote.transport.AssociationHandle]] might receive.
   */
  sealed trait HandleEvent extends NoSerializationVerificationNeeded

  /**
   * Message sent to the listener registered to an association (via the Promise returned by
   * [[akka.remote.transport.AssociationHandle#readHandlerPromise]]) when an inbound payload arrives.
   *
   * @param payload
   *   The raw bytes that were sent by the remote endpoint.
   */
  final case class InboundPayload(payload: ByteString) extends HandleEvent {
    override def toString: String = s"InboundPayload(size = ${payload.length} bytes)"
  }

  /**
   * Message sent to the listener registered to an association
   *
   * @param info
   *   information about the reason of disassociation
   */
  final case class Disassociated(info: DisassociateInfo) extends HandleEvent with DeadLetterSuppression

  /**
   * Supertype of possible disassociation reasons
   */
  sealed trait DisassociateInfo

  case object Unknown extends DisassociateInfo
  case object Shutdown extends DisassociateInfo
  case object Quarantined extends DisassociateInfo

  /**
   * An interface that needs to be implemented by the user of an [[akka.remote.transport.AssociationHandle]]
   * to listen to association events.
   */
  trait HandleEventListener {
    /**
     * Called by the transport to notify the listener about a HandleEvent
     * @param ev The HandleEvent of the handle
     */
    def notify(ev: HandleEvent): Unit
  }

  /**
   * Class to convert ordinary [[akka.actor.ActorRef]] instances to a HandleEventListener. The adapter will
   * forward event objects as messages to the provided ActorRef.
   * @param actor
   */
  final case class ActorHandleEventListener(actor: ActorRef) extends HandleEventListener {
    override def notify(ev: HandleEvent): Unit = actor ! ev
  }
}

/**
 * An SPI layer for abstracting over logical links (associations) created by a [[akka.remote.transport.Transport]].
 * Handles are responsible for providing an API for sending and receiving from the underlying channel.
 *
 * To register a listener for processing incoming payload data, the listener must be registered by completing the Promise
 * returned by [[akka.remote.transport.AssociationHandle#readHandlerPromise]]. Incoming data is not processed until
 * this registration takes place.
 */
trait AssociationHandle {

  /**
   * Address of the local endpoint.
   *
   * @return
   *   Address of the local endpoint.
   */
  def localAddress: Address

  /**
   * Address of the remote endpoint.
   *
   *  @return
   *   Address of the remote endpoint.
   */
  def remoteAddress: Address

  /**
   * The Promise returned by this call must be completed with an [[akka.remote.transport.AssociationHandle.HandleEventListener]]
   * to register a listener responsible for handling incoming payload. Until the listener is not registered the
   * transport SHOULD buffer incoming messages.
   *
   * @return
   *   Promise that must be completed with the listener responsible for handling incoming data.
   */
  def readHandlerPromise: Promise[HandleEventListener]

  /**
   * Asynchronously sends the specified payload to the remote endpoint. This method MUST be thread-safe as it might
   * be called from different threads. This method MUST NOT block.
   *
   * Writes guarantee ordering of messages, but not their reception. The call to write returns with
   * a Boolean indicating if the channel was ready for writes or not. A return value of false indicates that the
   * channel is not yet ready for delivery (e.g.: the write buffer is full) and the sender needs to wait
   * until the channel becomes ready again. Returning false also means that the current write was dropped (this MUST be
   * guaranteed to ensure duplication-free delivery).
   *
   * @param payload
   *   The payload to be delivered to the remote endpoint.
   * @return
   *   Boolean indicating the availability of the association for subsequent writes.
   */
  def write(payload: ByteString): Boolean

  /**
   * Closes the underlying transport link, if needed. Some transports might not need an explicit teardown (UDP) and
   * some transports may not support it (hardware connections). Remote endpoint of the channel or connection MAY
   * be notified, but this is not guaranteed. The Transport that provides the handle MUST guarantee that disassociate()
   * could be called arbitrarily many times.
   *
   */
  @deprecated(message = "Use method that states reasons to make sure disassociation reasons are logged.", since = "2.5.3")
  def disassociate(): Unit

  /**
   * Closes the underlying transport link, if needed. Some transports might not need an explicit teardown (UDP) and
   * some transports may not support it (hardware connections). Remote endpoint of the channel or connection MAY
   * be notified, but this is not guaranteed. The Transport that provides the handle MUST guarantee that disassociate()
   * could be called arbitrarily many times.
   */
  def disassociate(reason: String, log: LoggingAdapter): Unit = {
    if (log.isDebugEnabled)
      log.debug(
        "Association between local [{}] and remote [{}] was disassociated because {}",
        localAddress, remoteAddress, reason)

    disassociate()
  }
}

