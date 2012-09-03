package akka.remote.actmote

import akka.actor._
import akka.remote._
import akka.actor.Address
import akka.actor.Terminated
import akka.dispatch.SystemMessage
import akka.actor.Terminated
import akka.event.LoggingAdapter

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
   *  - must be sent before any [[akka.remote.actmote.TransportConnector.ConnectionInitialized]], [[akka.remote.actmote.TransportConnector.ConnectionFailed]] or [[akka.remote.actmote.TransportConnector.Disconnected]] has been sent or [[akka.remote.actmote.TransportConnectorHandle.dispatchMessage()]] has been called
   *  - is not allowed to be ''sent'' after [[akka.remote.actmote.TransportConnector.shutdown()]] has been called, although it might be ''received'' after the call.
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
   *  - must be sent before any [[akka.remote.actmote.TransportConnector.ConnectionInitialized]], [[akka.remote.actmote.TransportConnector.ConnectionFailed]], [[akka.remote.actmote.TransportConnector.Disconnected]] ohas been sent or [[akka.remote.actmote.TransportConnectorHandle.dispatchMessage()]] has been called
   *  - is not allowed to be ''sent'' after [[akka.remote.actmote.TransportConnector.shutdown()]] has been called, although it might be ''received'' after the call.
   *  - must be sent after the Connector successfully finished initialization and has been bound to a local address
   *  - must be sent at most once between calls [[akka.remote.actmote.TransportConnector.listen]] and [[akka.remote.actmote.TransportConnector.shutdown()]]
   *  - must be only sent to the responsible actor of the connector
   *  @param reason the cause of the failure
   */
  case class ConnectorFailed(reason: Throwable)

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
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a connection was initiated
   * by the responsible actor by calling [[akka.remote.actmote.TransportConnector.connect()]] and the connection has been
   * successfully established.
   *
   * === More specifically this message ===
   *  - is allowed to be sent only after [[akka.remote.actmote.TransportConnector.connect()]] has been already called
   *  - is not allowed to be sent after a [[akka.remote.actmote.TransportConnector.Disconnected]] or a [[akka.remote.actmote.TransportConnector.ConnectionFailed]] message has been sent to the corresponding actors
   *  - is not allowed to be sent after [[akka.remote.actmote.TransportConnectorHandle.close()]] has been called on the corresponding handle, but might be ''received'' after the call
   *  - must be sent before any message has been dispatched by calling [[akka.remote.actmote.TransportConnectorHandle.dispatchMessage()]]
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
   *  - is not allowed to be sent after a [[akka.remote.actmote.TransportConnector.Disconnected]]
   *  - is not allowed to be sent after [[akka.remote.actmote.TransportConnectorHandle.close()]] has been called on the corresponding handle, but might be ''received'' after the call
   *  - must be sent shortly after failing to establish an outbound connection, and it is a reasonable assumption, that retrying to establish the connection in some short time window will fail. In other words
   *    it is assumed, that a connection failure is only reported after retrying several times, or after applying any other failure handling mechanisms appropriate for the underlying transport.
   *  - must be sent to the actor specified as a parameter to the call [[akka.remote.actmote.TransportConnector.connect()]]
   * @param reason the cause of the failure
   */
  // TODO: separate case class for different failure cases?
  case class ConnectionFailed(reason: Throwable) extends ConnectorEvent

  /**
   * Sent to the responsible actor of a [[akka.remote.actmote.TransportConnectorHandle]] after a connection was closed
   * by the remote endpoint of the connection. It is the responsibility of the connector to detect disconnects when
   * using a connectionless transport (e.g UDP). Disconnect is a best-effort service, it is not guaranteed that
   * a Disconnect arrives for every possible channel close scenario.
   *
   * === More specifically this message ===
   *  - must be sent to the actor responsible for the handle
   *  - must be sent shortly after the remote endpoint has been successfully recognized to be closed
   *  - is not guaranteed to be sent after the remote endpoint closed its corresponding handle
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
 * Connectors need a responsible actor to whom their forward lifecycle messages. This must be set by calling listen() before any other
 * operation is invoked on the connector.
 *
 * Most of the calls are asynchronous and their behavior and assumptions regarding the invoker
 * are specified below.
 *
 * The overall behavior is specified by the following notation
 *  - A → B -- if A happens, then eventually B happens
 *  - A ← B -- if B happens, then A must have happened some time before B
 *  - A !→ B -- if A happens, B cannot happen afterwards
 *
 * === Startup ===
 *  - (call to listen(actor A)) → ((exception is thrown) || (ConnectorInitialized(address A) is sent to A) || (ConnectorFailed is sent to A))
 *  - (call to listen(actor A)) ← (call to connect())
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
   * Asynchronously attempts to start up the transport layer wrapped by the connector and sends
   * the bound local address after success to the responsible actor.
   *
   * === More specifically ===
   *  - listen() is allowed to be called at most once until a [[akka.remote.actmote.TransportConnector.shutdown()]] is called
   *  - after the call to listen() exactly one of the following events must happen and must happen exactly once:
   *    - initialization fails and an exception is thrown to the caller
   *    - initialization succeeds and a ConnectorInitialized message is eventually sent to the responsible actor containing the locally bound address
   *    - initialization fails and a ConnectorFailed message is eventually sent to the responsible actor containing a Throwable
   */
  def listen(responsibleActor: ActorRef): Unit

  /**
   * Asynchronously attempts to connect to the specified remote address and sends the handle representing the channel
   * after success to the actor provided as a parameter.
   *
   * === More specifically ===
   *  - connect() is only allowed to be called after listen() was called, and a [[akka.remote.actmote.TransportConnector.ConnectorInitialized]] message has been received and before shutdown() was called
   *  - after the call to connect exactly one of the following events must happen and must happen exactly once:
   *    - connection fails and an exception is thrown to the caller
   *    - connection succeeds and a ConnectionInitialized message is eventually sent to the specified actor
   *    - connection fails and a ConnectionFailed message is eventually sent to the specified actor
   *  - the caller of connect() can safely assume, that the connection is potentially unrecoverable if it indicates a failure. In other words, the caller can safely assume
   *  that the connector handles retries and other reasonable error handling mechanisms, so retries on the caller side will most likely fail and therefore unnecessary.
   * @param remote Remote address to be connected to
   * @param responsibleActorForConnection Actor that will receive the lifecycle events for the connection
   */
  def connect(remote: Address, responsibleActorForConnection: ActorRef): Unit

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
 * and receiving from the underlying channel and handle some lifecycle messages, notably Disconnect.
 *
 * Handles need a responsible actor to whom their forward lifecycle messages. This must be set by calling open() before any other
 * operation (except close()) is invoked on the connector.
 *
 * Most of the calls are asynchronous and their behavior and assumptions regarding the invoker
 * are specified below.
 *
 * The overall behavior is specified by the following notation
 *  - A → B -- if A happens, then eventually B happens
 *  - A ← B -- if B happens, then A must have happened some time before B
 *  - A !→ B -- if A happens, B cannot happen afterwards
 *
 * === Accepting outbound and inbound connection ===
 *  - (ConnectionInitialized(handle H) is sent to the actor specified in [[akka.remote.actmote.TransportConnector.connect()]]) ← (any other operation on H)
 *  - or
 *  - (IncomingConnection(handle H) is sent to the responsible actor of the corresponding connector) ← (any other operation on H)
 *
 * === Initialization ===
 *  - (call to open(actor A)) ← (any operation on the handle except close())
 *  - (call to open(actor A)) ← (the message is delivered via dispatchMessage(message M))
 *
 * '''NOTE: In rare cases the reception of ConnectionFailed() may happen before the actor called open().'''
 *
 * === Disconnecting and failures ===
 *  - (Disconnect is sent) !→ (any operation on the handle except close())
 *
 *
 * === Closing ===
 *  - (call to close()) !→ (any other operation on the handle)
 */
abstract class TransportConnectorHandle(val provider: RemoteActorRefProvider) {

  import akka.actor.{ Address, ActorRef }
  import akka.remote.RemoteActorRef

  /**
   * Returns the address of the remote endpoint of the channel this handle represents. This call is synchronous and
   * can be assumed to always succeed.
   * @return address of the remote endpoint
   */
  def remoteAddress: Address

  /**
   * Returns the address of the local endpoint of the channel this handle represents. This class is synchronous and
   * can be assumed to always succeed.
   * @return address of the local endpoint
   */
  def localAddress: Address

  /**
   * Notifies the [[akka.remote.actmote.TransportConnector]] that the responsibleActor is set on the handle, and the
   * actor is ready to receive events. If the handle represents an inbound channel it is the responsibility of
   * the connector to buffer arriving messages until the handle is opened. If the message buffer gets full, the Connector
   * may indicate failure by sending a ConnectionFailed message, or it may handle it a transport specific way.
   *
   * === More specifically ===
   *  - after close() have been called, it is not allowed to call open()
   *  - no write() calls are allowed on the handle before open() is called
   *  - no messages are dispatched via dispatchMessage() before open() is called. Any messages must be buffered by the connector
   *    until open() is called.
   */
  def open(responsibleActor: ActorRef): Unit

  /**
   * Asynchronously sends the specified message to a remote actor. The sender actor might be specified or omitted.
   * Writes guarantee ordering of messages, but not their reception. The call to write synchronously returns with
   * a Boolean indicating that the channel was ready at the time of call. Return value false indicated that the channel
   * is not ready for delivery and the sender needs to wait until the channel becomes ready again.
   *
   * === More specifically ===
   *  - write() is only allowed after open() has been called
   *  - after calling write(message M) with a return value true the connector eventually ''may'' deliver the message to the remote endpoint
   *    after which dispatchMessage(M) will be called on the handle of the remote endpoint.
   *  - for any two successful writes, write(A) and then write(B), exactly one of the following events will happen
   *   - no message is received
   *   - A is received
   *   - B is received
   *   - A and then B is received
   *  - for any two writes, write(A) and then write(B), none of the following events may happen
   *   - receiving multiple copies of A or B
   *   - receiving A after B
   *
   * @param msg message to be sent to the remote actor
   * @param senderOption optional sender reference
   * @param recipient recipient actor at the remote system
   * @return false if the channel is saturated and the sender needs to back off
   */
  def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef): Boolean

  /**
   * Closes the underlying transport channel. Remote endpoint of the channel or connection ''may'' be notified, but this
   * is not guaranteed in the case of connectionless transports.
   *
   * === More specifically ===
   *  - close() is allowed to be called at any time
   *  - no operations are allowed on the handle after close() has been called
   *  - the remote endpoint of the channel eventually ''may'' receive a Disconnect message
   */
  def close(): Unit

  /**
   * Call this method with an inbound RemoteMessage and this will take care of security (see: "useUntrustedMode")
   * as well as making sure that the message ends up at its destination (best effort).
   * There is also a fair amount of logging produced by this method, which is good for debugging.
   *
   * === More specifically ===
   *  - dispatchMessage() is allowed to be called only after an [[akka.remote.actmote.TransportConnector.IncomingConnection]] or a [[akka.remote.actmote.TransportConnector.ConnectionInitialized]] message has been already sent
   *  - dispatchMessage() is not allowed to be called after a [[akka.remote.actmote.TransportConnector.Disconnected]] or a [[akka.remote.actmote.TransportConnector.ConnectionFailed]] message has been sent for the corresponding handle
   *  - dispatchMessage() is not allowed to be called after the corresponding handle has been closed
   *  - must be called shortly after an incoming message has been successfully received and parsed by the transport
   *
   * @param remoteMessage the incoming message to be dispatched to the correct actor
   * @param log
   */
  // TODO: Think about the visibility of this method
  final def dispatchMessage(remoteMessage: RemoteMessage, log: LoggingAdapter): Unit = {
    val useUntrustedMode = provider.remoteSettings.UntrustedMode
    val log = provider.log
    val remoteDaemon = provider.remoteDaemon

    remoteMessage.recipient match {
      case `remoteDaemon` ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received daemon message {}", remoteMessage)
        remoteMessage.payload match {
          case m @ (_: DaemonMsg | _: Terminated) ⇒
            try remoteDaemon ! m catch {
              case e: Exception ⇒ log.error(e, "exception while processing remote command {} from {}", m, remoteMessage.sender)
            }
          case x ⇒ log.warning("remoteDaemon received illegal message {} from {}", x, remoteMessage.sender)
        }
      case l @ (_: LocalRef | _: RepointableRef) if l.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received local message {}", remoteMessage)
        remoteMessage.payload match {
          case msg: PossiblyHarmful if useUntrustedMode ⇒ log.warning("operating in UntrustedMode, dropping inbound PossiblyHarmful message of type {}", msg.getClass)
          case msg: SystemMessage                       ⇒ l.sendSystemMessage(msg)
          case msg                                      ⇒ l.!(msg)(remoteMessage.sender)
        }
      case r @ (_: RemoteRef | _: RepointableRef) if !r.isLocal ⇒
        if (provider.remoteSettings.LogReceive) log.debug("received remote-destined message {}", remoteMessage)
        remoteMessage.originalReceiver match {
          case AddressFromURIString(address) if address == provider.transport.address ⇒
            // if it was originally addressed to us but is in fact remote from our point of view (i.e. remote-deployed)
            r.!(remoteMessage.payload)(remoteMessage.sender)
          case r ⇒ log.error("dropping message {} for non-local recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, localAddress, provider.transport.address)
        }
      case r ⇒ log.error("dropping message {} for unknown recipient {} arriving at {} inbound address is {}", remoteMessage.payload, r, localAddress, provider.transport.address)
    }
  }
}

