package akka.remote.transport

import concurrent.{ Promise, Future }
import akka.actor.{ ActorRef, Address }
import akka.util.ByteString

object Transport {

  /**
   * Represents fine grained status of an association attempt.
   */
  sealed trait Status

  /**
   * Indicates that the association setup request is invalid, and it is impossible to recover (malformed IP address,
   * hostname, etc.). Invalid association requests are impossible to recover.
   */
  case object Invalid extends Status

  /**
   * The association setup has failed, but no information can be provided about the probability of the success of a
   * setup retry.
   *
   * @param cause Cause of the failure
   */
  case class Fail(cause: Throwable) extends Status

  /**
   * No detectable errors happened during association. Generally a status of Ready does not guarantee that the
   * association was successful. For example in the case of UDP, the transport MAY return Ready immediately after an
   * association setup was requested.
   *
   * @param association
   *   The handle for the created association.
   */
  case class Ready(association: AssociationHandle) extends Status

  /**
   * Message sent to an actor registered to a transport (via the Promise returned by
   * [[akka.remote.transport.Transport.listen]]) when an inbound association request arrives.
   *
   * @param association
   *   The handle for the inbound association.
   */
  case class InboundAssociation(association: AssociationHandle)

}

/**
 * An SPI layer for implementing asynchronous transport mechanisms. The transport is responsible for initializing the
 * underlying transport mechanism and setting up logical links between transport entities.
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
   * ActorRef. By completing this Promise with an ActorRef, that ActorRef becomes responsible for handling incoming
   * associations. Until the Promise is not completed, no associations are processed.
   *
   * @return
   *   A Future containing a pair of the bound local address and a Promise of an ActorRef that must be fulfilled
   *   by the consumer of the future.
   */
  def listen: Future[(Address, Promise[ActorRef])]

  /**
   * Asynchronously opens a logical duplex link between two Transport Entities over a network. It could be backed by a
   * real transport-layer connection (TCP), more lightweight connections provided over datagram protocols (UDP with
   * additional services), substreams of multiplexed connections (SCTP) or physical links (serial port).
   *
   * This call returns a fine-grained status indication of the attempt wrapped in a Future. See
   * [[akka.remote.transport.Transport.Status]] for details.
   *
   * @param remoteAddress
   *   The address of the remote transport entity.
   * @return
   *   A status instance representing failure or a success containing an [[akka.remote.transport.AssociationHandle]]
   */
  def associate(remoteAddress: Address): Future[Status]

  /**
   * Shuts down the transport layer and releases all the corresponding resources. Shutdown is asynchronous, may be
   * called multiple times and does not return a success indication.
   */
  def shutdown(): Unit

}

object AssociationHandle {

  /**
   * Trait for events that the registered actor for an [[akka.remote.transport.AssociationHandle]] might receive.
   */
  sealed trait AssociationEvent

  /**
   * Message sent to the actor registered to an association (via the Promise returned by
   * [[akka.remote.transport.AssociationHandle.readHandlerPromise]]) when an inbound payload arrives.
   *
   * @param payload
   *   The raw bytes that were sent by the remote endpoint.
   */
  case class InboundPayload(payload: ByteString) extends AssociationEvent

  /**
   * Message sent to te actor registered to an association
   */
  case object Disassociated extends AssociationEvent

}

/**
 * An SPI layer for abstracting over logical links (associations) created by [[akka.remote.transport.Transport]].
 * Handles are responsible for providing an API for sending and receiving from the underlying channel.
 *
 * To register an actor for processing incoming payload data, the actor must be registered by completing the Promise
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
   * The Promise returned by this call must be completed with an [[akka.actor.ActorRef]] to register an actor
   * responsible for handling incoming payload.
   *
   * @return
   *   Promise of the ActorRef of the actor responsible for handling incoming data.
   */
  def readHandlerPromise: Promise[ActorRef]

  /**
   * Asynchronously sends the specified payload to the remote endpoint. This method must be thread-safe as it might
   * be called from different threads. This method must not block.
   *
   * Writes guarantee ordering of messages, but not their reception. The call to write returns with
   * a Boolean indicating if the channel was ready for writes or not. A return value of false indicates that the
   * channel is not yet ready for delivery (e.g.: the write buffer is full) and the sender needs to wait
   * until the channel becomes ready again. Returning false also means that the current write was dropped (this is
   * guaranteed to ensure duplication-free delivery).
   *
   * @param payload
   *   The payload to be delivered to the remote endpoint.
   * @return
   *   Boolean indicating the availability of the association for subsequent writes.
   */
  def write(payload: ByteString): Boolean

  /**
   * Closes the underlying transport link, if needed. Some transport may not need an explicit teardown (UDP) and
   * some transports may not support it (hardware connections). Remote endpoint of the channel or connection ''may''
   * be notified, but this is not guaranteed.
   *
   */
  def disassociate(): Unit

}

