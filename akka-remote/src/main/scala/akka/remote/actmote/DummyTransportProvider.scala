package akka.remote.actmote

import akka.remote._
import akka.remote.netty.NettySettings
import akka.actor._
import akka.remote.RemoteProtocol._
import akka.serialization.Serialization

class DummyTransportProvider(val system: ExtendedActorSystem, val provider: RemoteActorRefProvider) extends TransportProvider {
  import DummyTransportMedium._

  val lock = new AnyRef
  val settings = new NettySettings(provider.remoteSettings.config.getConfig("akka.remote.netty"), provider.remoteSettings.systemName)

  // Should think about the lifecycle of the handler (null -> ref -> null) and concurrency issuess
  private var _connectionHandler: TransportHandle ⇒ Unit = _
  private var _isTerminated = false
  private var handles = List[DummyHandle]()

  def connectionHandler = lock synchronized _connectionHandler
  def isTerminated = lock synchronized _isTerminated

  // Access should be synchronized?

  val address = Address("akka", provider.remoteSettings.systemName, settings.Hostname, settings.PortSelector) // Does not handle dynamic ports, used only for testing

  registerTransport(address, this)

  def shutdown() {
    // Remove locks if possible
    lock.synchronized {
      if (_isTerminated) throw new IllegalStateException("Cannot shutdown: already terminated")
      handles foreach { _.close }
      _isTerminated = true
    }
  }

  def connect(remote: Address, onSuccess: TransportHandle ⇒ Unit, onFailure: Throwable ⇒ Unit) {
    lock.synchronized {
      if (_isTerminated) throw new IllegalStateException("Cannot connect: already terminated")

      DummyTransportMedium.activityLog ::= ConnectionAttempt(address -> remote)

      // Instead of using different sets, use a map of options and patter matching instead of ifs
      if (DummyTransportMedium.crashSet(address)) {
        throw new NullPointerException
      } else if (DummyTransportMedium.rejectSet(address)) {
        onFailure(new IllegalStateException("Rejected"))
      } else if (!DummyTransportMedium.droppingSet(address)) {

        val connectionResult: Option[(DummyHandle, DummyHandle)] = registerConnection(address -> remote, this)
        // TODO: rewrite it using match - case
        if (connectionResult.isDefined) {
          val (localHandle, remoteHandle) = connectionResult.get
          handles ::= localHandle
          onSuccess(localHandle)
        } else {
          onFailure(new IllegalArgumentException("Remote address does not reachable"))
        }
      }
    }
  }

  def setConnectionHandler(handler: TransportHandle ⇒ Unit) {
    lock.synchronized {
      _connectionHandler = handler
    }
  }

  override def toString = "DummyTransport(" + address + ")"
}

class DummyHandle(val owner: DummyTransportProvider, val address: Address, val remoteAddress: Address, val server: Boolean) extends TransportHandle {
  import DummyTransportMedium._

  val key = if (server) remoteAddress -> address else address -> remoteAddress
  // TODO: This emulates queueing at the receiver side until the connection is accepted
  @volatile var queue = List[RemoteMessage]()
  @volatile var handler: RemoteMessage ⇒ Unit = (msg) ⇒ queue ::= msg

  def close() {
    // TODO: Notify other endpoint
    if (owner.isTerminated) throw new IllegalStateException("Cannot close handle: transport already terminated")
    removeConnection(key)
  }

  def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    if (owner.isTerminated) throw new IllegalStateException("Cannot write to handle: transport already terminated")
    DummyTransportMedium.activityLog ::= SendAttempt(msg, address, remoteAddress)
    handlesForConnection(key) match {
      case Some((clientHandle, serverHandle)) ⇒ {
        val remoteHandle = if (server) clientHandle else serverHandle
        val msgProtocol = createRemoteMessageProtocolBuilder(recipient, msg, senderOption).build
        remoteHandle.handler(new RemoteMessage(msgProtocol, remoteHandle.owner.system))
      }
      case None ⇒ // Error
    }
  }

  def setReadHandler(handler: RemoteMessage ⇒ Unit) {
    // ACHTUNG!! We must dequeue the messages in the queue first! Not thread safe at all, must think about another solution
    queue.reverse.foreach { msg ⇒ handler(msg) }
    this.handler = handler
  }

  // --- Methods lifted shamelessly from RemoteTransport
  private def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(address)).build

  /**
   * Returns a new RemoteMessageProtocol containing the serialized representation of the given parameters.
   */
  private def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {
    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(address) {
      messageBuilder.setMessage(MessageSerializer.serialize(owner.system, message.asInstanceOf[AnyRef]))
    }

    messageBuilder
  }
}

object DummyTransportMedium {
  sealed trait Activity
  case class ConnectionAttempt(link: (Address, Address)) extends Activity
  case class SendAttempt(msg: Any, sender: Address, recipient: Address) extends Activity

  @volatile private var transportTable = Map[Address, DummyTransportProvider]()
  @volatile private var connectionTable = Map[(Address, Address), (DummyHandle, DummyHandle)]()

  @volatile var activityLog = List[Activity]()

  @volatile var droppingSet = Set[Address]()
  @volatile var rejectSet = Set[Address]()
  @volatile var crashSet = Set[Address]()

  def silentDrop(source: Address) {
    droppingSet += source
  }

  def crash(source: Address) {
    crashSet += source
  }

  def reject(source: Address) {
    rejectSet += source
  }

  def allow(source: Address) {
    droppingSet -= source
    rejectSet -= source
    crashSet -= source
  }

  def clear() {
    transportTable = Map[Address, DummyTransportProvider]()
    connectionTable = Map[(Address, Address), (DummyHandle, DummyHandle)]()
    activityLog = List[Activity]()
  }

  def lookupTransport(address: Address) = transportTable.get(address)

  def existsTransport(address: Address) = transportTable.contains(address)

  def registerTransport(address: Address, transport: DummyTransportProvider) {
    transportTable += address -> transport
  }

  def isConnected(link: (Address, Address)): Boolean = connectionTable.contains(link)

  def registerConnection(link: (Address, Address), clientProvider: DummyTransportProvider): Option[(DummyHandle, DummyHandle)] = connectionTable.get(link) match {
    case Some(handlePair) ⇒ Some(handlePair)
    case None ⇒ {
      val (address, remote) = link

      // TODO: replace with foreach
      val remoteTransport: Option[DummyTransportProvider] = lookupTransport(remote)
      if (remoteTransport.isDefined) {
        val handlePair = new DummyHandle(clientProvider, address, remote, false) -> new DummyHandle(remoteTransport.get, remote, address, true)
        connectionTable += link -> handlePair
        remoteTransport.get.connectionHandler(handlePair._2)
        Some(handlePair)
      } else {
        None
      }
    }
  }

  def removeConnection(link: (Address, Address)) {
    connectionTable = connectionTable - link
  }

  def handlesForConnection(link: (Address, Address)): Option[(DummyHandle, DummyHandle)] = connectionTable.get(link)

}

