package akka.remote.actmote

import akka.actor._
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteMessage
import akka.actor.Address

// TODO: have a better name
// TODO: Use futures instead of callbacks??
// TODO: Have a TestKit for Connectors

/**
 * Contains all the event classes that a [[akka.remote.actmote.TransportConnector]] or
 * [[akka.remote.actmote.TransportConnectorHandle]] may send to their corresponding actors.
 */
object TransportConnector {

  /**
   * Base trait for all the connector events.
   */
  sealed trait ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnector]] after the connector
   * has been successfully initialized and bound to a local address.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after [[akka.remote.actmote.TransportConnector.listen]] has been already called and the startup has been successful
   *  - must be sent before any [[akka.remote.actmote.TransportConnector.ConnectionInitialized]], [[akka.remote.actmote.TransportConnector.ConnectionFailed]], [[akka.remote.actmote.TransportConnector.Disconnected]] or [[akka.remote.actmote.TransportConnector.MessageArrived]] has been sent
   *  - is not allowed to be ''sent'' after [[akka.remote.actmote.TransportConnector.shutdown()]] has been called, although it might be received'' after the call.
   *  - must be sent after the Connector successfully finished initialization and has been bound to a local address
   *  - must be sent at most once between calls [[akka.remote.actmote.TransportConnector.listen]] and [[akka.remote.actmote.TransportConnector.shutdown()]]
   *  - must be only sent to the responsible actor of the connector
   * @param bindAddress The local address the connector is bound to
   */
  case class ConnectorInitialized(bindAddress: Address) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnector]] after the connector
   * has been successfully initialized and bound to a local address.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after [[akka.remote.actmote.TransportConnector.listen]] has been already called and the startup has been successful
   *  - must be sent before any [[akka.remote.actmote.TransportConnector.ConnectionInitialized]], [[akka.remote.actmote.TransportConnector.ConnectionFailed]], [[akka.remote.actmote.TransportConnector.Disconnected]] or [[akka.remote.actmote.TransportConnector.MessageArrived]] has been sent
   *  - is not allowed to be ''sent'' after [[akka.remote.actmote.TransportConnector.shutdown()]] has been called, although it might be ''received'' after the call.
   *  - must be sent after the Connector successfully finished initialization and has been bound to a local address
   *  - must be sent at most once between calls [[akka.remote.actmote.TransportConnector.listen]] and [[akka.remote.actmote.TransportConnector.shutdown()]]
   *  - must be only sent to the responsible actor of the connector
   */
  case object ConnectorFailed

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnector]] after an inbound connection
   * has been successfully accepted and initialized by the connector.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after a [[akka.remote.actmote.TransportConnector.ConnectorInitialized]] message has been already sent
   *  - is not allowed to be ''sent'' after [[akka.remote.actmote.TransportConnector.shutdown()]] has been called, although it might be ''received'' after the call
   *  - must be sent shortly after an inbound connection has been successfully accepted ad initialized and the handle is ready for use
   *  - must be only sent to the responsible actor of the connector
   * @param handle The handle representing the incoming connection. The responsible actor on this handle has to be set
   */
  case class IncomingConnection(handle: TransportConnectorHandle) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a message has been
   * successfully received by the underlying transport layer.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after an [[akka.remote.actmote.TransportConnector.IncomingConnection]] or a [[akka.remote.actmote.TransportConnector.ConnectionInitialized]] message has been already sent
   *  - is not allowed to be sent after a [[akka.remote.actmote.TransportConnector.Disconnected]] or a [[akka.remote.actmote.TransportConnector.ConnectionFailed]] message has been sent for the corresponding handle
   *  - is not allowed to be ''sent'' after the corresponding handle has been closed, although it might be ''received'' after the call
   *  - must be sent shortly after an incoming message has been successfully received and parsed by the transport
   *  - must be only sent to the responsible actor of the handle
   *@param msg The [[akka.remote.RemoteMessage]] containing the received Akka message
   */
  case class MessageArrived(msg: RemoteMessage) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a connection was initiated
   * by the responsible actor by calling [[akka.remote.actmote.TransportConnector.connect()]] and the connection has been
   * successfully established.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after [[akka.remote.actmote.TransportConnector.connect()]] has been already called
   *  - is not allowed to be sent after a [[akka.remote.actmote.TransportConnector.Disconnected]] or a [[akka.remote.actmote.TransportConnector.ConnectionFailed]] message has been sent to the corresponding actors
   *  - is not allowed to be sent after [[akka.remote.actmote.TransportConnectorHandle.close()]] has been called on the corresponding handle, but might be ''received'' after the call
   *  - must be sent before any [[akka.remote.actmote.TransportConnector.MessageArrived]] message has been sent for the handle
   *  - must be sent shortly after an outbound connection is successfully established and the handle is ready for use
   *  - must be sent to the actor specified as a parameter to the call [[akka.remote.actmote.TransportConnector.connect()]]
   * @param handle the handle representing the established connection
   */
  case class ConnectionInitialized(handle: TransportConnectorHandle) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a connection was initiated
   * by the responsible actor by calling [[akka.remote.actmote.TransportConnector.connect()]] but the connection could not be
   * established.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after [[akka.remote.actmote.TransportConnector.connect()]] has been already called
   *  - is not allowed to be sent after a [[akka.remote.actmote.TransportConnector.Disconnected]] or a [[akka.remote.actmote.TransportConnector.ConnectorInitialized]] message has been sent to the corresponding actors
   *  - is not allowed to be sent after [[akka.remote.actmote.TransportConnectorHandle.close()]] has been called on the corresponding handle, but might be ''received'' after the call
   *  - must be sent shortly after failing to establish an outbound connection, and it is a reasonable assumption, that retrying to establish the connection in some short time window will fail. In other words
   *    it is assumed, that a connection failure is only reported after retrying several times, or after applying any other failure handling mechanisms appropriate for the underlying transport.
   *  - must be sent to the actor specified as a parameter to the call [[akka.remote.actmote.TransportConnector.connect()]]
   * @param reason
   */
  // TODO: separate case class for any handle failure?
  case class ConnectionFailed(reason: Throwable) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a connection was closed
   * by the remote endpoint of the connection. It is the responsibility of the connector to detect disconnects when
   * using a connectionless transport (e.g UDP).
   *
   * === More specifically this message ===
   *  - must be sent to the actor responsible for the handle
   *  - must be sent shortly after the remote endpoint closed the connection
   * @param handle
   */
  case class Disconnected(handle: TransportConnectorHandle) extends ConnectorEvent
}

/**
 * An SPI layer for implementing asynchronous transport mechanisms to be used by
 * [[akka.remote.actmote.ActorManagedRemoting]]. Connectors are responsible for setting up
 * an underlying transport mechanism, creating connections and returning [[akka.remote.actmote.TransportConnectorHandle]]
 * objects representing the created communication channels.
 *
 * Connectors need a responsible actor to whom their forward lifecycle messages. This must be set before any other
 * operation is invoked on the connector.
 *
 * Most of the calls are asynchronous and their behavior and assumptions concerning the invoker
 * are specified below.
 *
 * The overall behavior is specified by the following notation
 *  - A → B -- if A happens, then eventually B happens
 *  - A ← B -- if B happens, then A must have happened some time before B
 *  - A !→ -- if A happens, B cannot happen afterwards
 *
 * === Startup ===
 *  - (setting responsibleActor) ← (any operation on the Connector except shutdown())
 *  - (call to listen()) → ((exception is thrown) || (ConnectorInitialized(address A) is sent) || (ConnectorFailed is sent))
 *  - (call to listen()) ← (call to connect())
 *  - (ConnectorFailed is sent) !→ (any other operation on the connector except shutdown())
 *
 * === Connecting outbound ===
 *  - (call to connect(actor C)) → ((exception is thrown) || (ConnectionInitialized(handle H) is sent to C) || (ConnectionFailed is sent to C))
 *  - (Connection safely established and handle H is created) ← (ConnectionInitialized(H))
 *  - (ConnectionInitialized(handle H) ← (any operation on H)) see [[akka.remote.actmote.TransportConnectorHandle]] for details
 *
 * === Receiving inbound connections ===
 *  - (call to listen()) ← (IncomingConnection(handle H) is sent)
 *  - (Connection safely accepted and handle H is created) ← (IncomingConnection(handle H) is sent)
 *
 * === Shutting down ===
 *  - (call to shutdown()) !→ (messages sent to the responsible actor)
 *  - (call to shutdown() → (all corresponding handles are closed))
 *
 * @param system The ExtendedActorSystem where the Connector belongs
 * @param provider The RemoteActorRefProvider of the actor-system where the Connector belongs
 */
abstract class TransportConnector(val system: ExtendedActorSystem, val provider: RemoteActorRefProvider) {

  /**
   * Returns the actor responsible for handling the lifecycle events of the Connector
   * @return ActorRef to the responsible actor
   */
  def responsibleActor: ActorRef

  /**
   * Sets the actor responsible for handling the lifecycle events of the Connector
   * @param actor the actor that will receive the lifecycle events from the connector
   */
  def responsibleActor_=(actor: ActorRef): Unit

  /**
   * Asynchronously attempts to start up the transport layer wrapped by the connector and sends
   * the bound local address after success to the responsible actor.
   *
   * === More specifically ===
   *  - listen() is not allowed to be called before the responsible actor is set
   *  - listen() is allowed to be called at most once until a [[akka.remote.actmote.TransportConnector.shutdown()]] is called
   *  - after the call to listen() exactly one of the following events must happen and must happen exactly once:
   *    - initialization fails and an exception is thrown to the caller
   *    - initialization succeeds and a ConnectorInitialized message is eventually sent to the responsible actor containing the locally bound address
   *    - initialization fails and a ConnectorFailed message is eventually sent to the responsible actor containing a Throwable
   */
  def address: Address //TODO: change to listen with the semantics

  /**
   * Asynchronously attempts to connect to the specified remote address and sends the handle representing the channel
   * after success to the actor provided as a parameter. If this parameter is not specified the responsible actor for
   * the connector is used.
   *
   * === More specifically ===
   *  - connect() is only allowed to be called after listen() was called, and a [[akka.remote.actmote.TransportConnector.ConnectorInitialized]] message has been received and before shutdown() was called
   *  - after the call to connect exactly one of the following events must happen and must happen exactly once:
   *    - connection fails and an exception is thrown to the caller
   *    - connection succeeds and a ConnectionInitialized message is eventually sent to the specified actor (or the responsible actor for the connector if no actor is specified) containing the handle for the connection
   *    - connection fails and a ConnectionFailed message is eventually sent to the specified actor (or the responsible actor for the connector if no actor is specified) containing the handle for the connection
   *  - the caller of connect() can safely assume, that the connection is potentially unrecoverable if it indicates a failure. In other words, the caller can safely assume
   *  that the connector handles retries and other reasonable error handling mechanisms, so retries on the caller side will most likely fail and therefore unnecessary.
   * @param remote Remote address to be connected to
   * @param responsibleActorForConnection Actor that will receive the lifecycle events for the connection
   */
  def connect(remote: Address, responsibleActorForConnection: ActorRef = responsibleActor): Unit

  /**
   * Shuts down the transport layer wrapped by the connector and releases all the corresponding resources.
   *
   * === More specifically ===
   *  - shutdown() is allowed to be called at any time
   *  - after shutdown all calls to connect will fail with an exception
   *  - after shutdown no messages are sent to the responsible actor for the connector
   *  - after shutdown, all the handles corresponding to the connector ''eventually'' become closed
   */
  def shutdown(): Unit
}

/**
 * An SPI layer for abstracting communication channels created by [[akka.remote.actmote.TransportConnector]] to be used by
 * [[akka.remote.actmote.ActorManagedRemoting]]. Handles are responsible for providing an API for sending
 * and receiving from the underlying channel and handle some lifecylce messages, notably Disconnect.
 *
 * Handles need a responsible actor to whom their forward lifecycle messages. This must be set before any other
 * operation is invoked on the connector.
 *
 * Most of the calls are asynchronous and their behavior and assumptions concerning the invoker
 * are specified below.
 *
 * The overall behavior is specified by the following notation
 *  - A → B -- if A happens, then eventually B happens
 *  - A ← B -- if B happens, then A must have happened some time before B
 *  - A !→ -- if A happens, B cannot happen afterwards
 *
 * === Accepting outbound and inbound connection ===
 *  - (ConnectionInitialized(handle H) is sent to the actor specified in [[akka.remote.actmote.TransportConnector.connect()]]) ← (any other operation on H)
 *  - or
 *  - (IncomingConnection(handle H) is sent to the responsible actor of the corresponding connector) ← (any other operation on H)
 *
 * === Initialization ===
 *  - (setting responsibleActor) ← (call to open())
 *  - (call to open()) ← (any operation on the handle except close())
 *  - (call to open()) ← (MessageArrived(message M) is sent to the responsible actor)
 *
 * '''NOTE: In rare cases the reception of ConnectionFailed() happens before the actor even called stop.'''
 *
 * === Disconnecting and failures ===
 *  - (Disconnect is sent) !→ (any operation on the handle except close())
 *

 * === Closing ===
 *  - (call to close()) !→ (any other operation on the handle)
 */
trait TransportConnectorHandle {

  import akka.actor.{ Address, ActorRef }
  import akka.remote.RemoteActorRef

  /**
   * Returns the actor responsible for handling the lifecycle events of the Handle
   * @return the responsible actor
   */
  def responsibleActor: ActorRef

  /**
   * Sets the actor responsible for handling the lifecycle events of the Handle
   * @param actor the actor that will receive the lifecycle events from the handle
   */
  def responsibleActor_=(actor: ActorRef): Unit

  /**
   * Returns the address of the remote endpoint of the channel this handle represents. This call is synchronous and
   * can be assumed to always succeed.
   * @return address of the remote endpoint
   */
  def remoteAddress: Address

  // TODO: fire off a Future when everything is ready on the handle
  /**
   * Notifies the [[akka.remote.actmote.TransportConnector]] that the responsibleActor is set on the handle, and the
   * actor is ready to receive events. If the handle represents an inbound connection it is the responsibility of
   * the connector to buffer arriving messages until the handle is opened. If the message buffer gets full, the Connector
   * may indicate failure by sending a ConnectionFailed message, or it may handle it a transport specific way.
   *
   * === More specifically ===
   *  - open() is only allowed to be called after the responsible actor has been set
   *  - after close() have been called, it is not allowed to call open()
   *  - no write() calls are allowed on the handle before open() is called
   *  - no MessageArrived messages are sent before open() is called. Any messages must be buffered by the connector
   *    until open() is called.
   */
  def open()

  /**
   * Asynchronously sends the specified message to a remote actor. The sender actor might be specified or omitted.
   * Writes guarantee ordering of messages, but not their reception.
   *
   * === More specifically ===
   *  - write() is only allowed after open() has been called
   *  - after calling write(message M) the connector eventually ''may'' deliver the message to the remote endpoint
   *    and a MessageArrived(M) event is sent to its responsible actor.
   * - for any two writes, write(A) and then write(B), exactly one of the following events will happen
   *   - no message is received
   *   - A is received
   *   - B is received
   *   - A and then B is received
   *  - for any two writes, write(A) and then write(B), none of the following events may happen
   *   - receiving multiple copies of A or B
   *   - receivng A after B
   *
   * @param msg message to be sent to the remote actor
   * @param senderOption optional sender reference
   * @param recipient recipient actor at the remote system
   */
  def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Unit

  /**
   * Closes the underlying transport channel. Remote endpoint of the channel or connection ''may'' be notified, but this
   * is not guaranteed in the case of connectionless transports.

   * === More specifically ===
   *  - close() is allowed to be called at any time
   *  - no operations are allowed on the handle after close() has been called
   *  - the remote endpoint of the channel eventually ''may'' receive a Disconnect message
   */
  def close(): Unit
}

