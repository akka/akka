package akka.remote.transport

import concurrent.{Promise, Future}
import akka.actor.{ActorRef, Address}
import akka.util.ByteString

object Transport {

  /**
   * Represents fine grained status of an association setup attempt.
   */
  sealed trait Status

  /**
   * The association setup request is invalid, and it is impossible to recover (malformed IP address, hostname, etc.).
   * Invalid association requests are impossible to recover.
   */
  case object Invalid extends Status

  /**
   * The association setup has failed immediately and a setup retry will fail with high probability in a short time
   * window. Examples for this are link errors that are detected by the Transport (no cable attached, no carrier
   * detected, etc...), problems with Addresses (DNS lookup problems).
   */
  case object ImmediateFail extends Status

  /**
   * The association setup has failed, but no information can be provided about the probability of the success of a
   * setup retry.
   */
  case object Fail extends Status

  /**
   * No detectable errors happened during Setup. Generally a status of Ready does not guarantee that the Setup was
   * successful. For example in the case of UDP, the transport MAY return Ready immediately after an association setup
   * was requested.
   *
   * @param association
   *   The handle for the created association.
   */
  case class Ready(association: AssociationHandle) extends Status

  /**
   * Message sent to an actor registered to a transport (via the Promise returned by
   * [[akka.remote.transport.Transport.listen]]) when an inbound association is present.
   *
   * @param association
   *   The handle for the inbound association.
   */
  case class InboundAssociation(association: AssociationHandle)
}

/**
 * An SPI layer for implementing asynchronous transport mechanisms. The transport is responsible for initializing the
 * underlying transport mechanism and setting up logical links between transport entities.
 */
trait Transport {
  import akka.remote.transport.Transport._

  /**
   * Asynchronously attempts to setup the transport layer to listen and accept incoming associations. The result of the
   * attempt is wrapped by a Future returned by this method. The pair contained in the future contains a Promise for an
   * ActorRef. By completing this Promise with an ActorRef that ActorRef becomes responsible for handling incoming
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
   * This call returns a fine-grained status indication of the attempt in the future. See
   * [[akka.remote.transport.Transport.Status]] for details.
   *
   * @param remoteAddress
   *   The address of the remote transport entity.
   * @return
   *   A status instance representing failure or a success containing an [[akka.remote.transport.AssociationHandle]]
   */
  def associate(remoteAddress: Address): Future[Status]

  /**
   * Shuts down the transport layer and releases all the corresponding resources.
   *
   * @return
   *   A Future that completes after shutdown is completed
   */
  def shutdown(): Future[Unit]

}

object AssociationHandle {
  /**
   * Message sent to an actor registered to an association (via the Promise returned by
   * [[akka.remote.transport.AssociationHandle.readHandlerPromise]]) when an inbound payload arrives.
   *
   * @param payload
   *   The raw bytes that were sent by the remote endpoint.
   */
  case class Receive(payload: ByteString)
}

/**
 * An SPI layer for abstracting over logical links (associations) created by [[akka.remote.transport.Transport]].
 * Handles are responsible for providing an API for sending and receiving from the underlying channel.
 *
 * To register an actor for processing incoming payload data, the actor must be registered by completing the Promise
 * returned by [[akka.remote.transport.AssociationHandle.readHandlerPromise]]. Incoming data is not processed until
 * this registration takes place.
 */
trait AssociationHandle {

  /**
   * Address of the local endpoint.
   *
   * @return
   *   Address of the local endpoint.
   */
  def localEndpointAddress: Address

  /**
   * Address of the remote endpoint.
   *
   *  @return
   *   Address of the remote endpoint.
   */
  def remoteEndpointAddress: Address

  /**
   * The Promise returned by this call must be completed with an [[akka.actor.ActorRef]] to register an actor
   * responsible for handling incoming data.
   *
   * @return
   *   Promis of the ActorRef of the actor responsible for handling incoming data.
   */
  def readHandlerPromise: Promise[ActorRef]

  /**
   * Asynchronously sends the specified payload to the remote endpoint.
   *
   * Writes guarantee ordering of messages, but not their reception. The call to write returns with a Future containing
   * a Boolean indicating that the channel is ready for more writes or not. A return value of false indicates that the
   * channel is not yet ready for delivery (e.g.: the write buffer is full) and the sender needs to wait
   * until the channel becomes ready again.
   *
   * @param payload
   *   The payload to be delivered to the remote endpoint.
   * @return
   *   Boolean indicating the availability of the association for subsequent writes.
   */
  def write(payload: ByteString): Future[Boolean]

  /**
   * Closes the underlying transport link, if needed. Some transport may not need an explicit teardown (UDP) and
   * some transports may not support it (hardware connections). Remote endpoint of the channel or connection ''may''
   * be notified, but this is not guaranteed.
   *
   * @return
   *   A Future that completes when the disassociation is finished and the corresponding resources released
   */
  def disassociate(): Future[Unit]

}

