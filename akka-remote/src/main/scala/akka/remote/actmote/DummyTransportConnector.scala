package akka.remote.actmote

import akka.remote._
import actmote.DummyHandle.HandleState
import akka.remote.netty.NettySettings
import akka.actor._
import akka.remote.RemoteProtocol._
import akka.serialization.Serialization
import actmote.TransportConnector._

class DummyTransportConnector(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends TransportConnector(_system, _provider) {
  import DummyTransportMedium._

  // TODO: get rid of lock if possible
  val lock = new AnyRef
  val settings = new NettySettings(provider.remoteSettings.config.getConfig("akka.remote.netty"), provider.remoteSettings.systemName)

  @volatile var responsibleActor: ActorRef = _

  private var _isTerminated = false
  private var handles = List[DummyHandle]()

  def isTerminated = lock synchronized {
    _isTerminated && handles.filter(_.state != DummyHandle.Closed).isEmpty
  }

  // Access should be synchronized?

  override def listen(responsibleActor: ActorRef) {
    this.responsibleActor = responsibleActor
    responsibleActor ! ConnectorInitialized(address)
  }

  val address = Address("akka", provider.remoteSettings.systemName, settings.Hostname, settings.PortSelector) // Does not handle dynamic ports, used only for testing

  registerTransport(address, this)

  override def shutdown() {
    // Remove locks if possible
    lock.synchronized {
      if (_isTerminated) throw new IllegalStateException("Cannot shutdown: already terminated")
      //handles foreach { _.close }
      _isTerminated = true
    }
  }

  override def connect(remote: Address, responsibleActorForConnection: ActorRef) {
    lock.synchronized {
      if (_isTerminated) throw new IllegalStateException("Cannot connect: already terminated")

      DummyTransportMedium.activityLog ::= ConnectionAttempt(address -> remote)

      // Instead of using different sets, use a map of options and patter matching instead of ifs
      if (DummyTransportMedium.crashSet(address)) {
        throw new NullPointerException
      } else if (DummyTransportMedium.rejectSet(address)) {

        responsibleActorForConnection ! ConnectionFailed(new IllegalStateException("Rejected"))
      } else if (!DummyTransportMedium.droppingSet(address)) {

        val connectionResult: Option[(DummyHandle, DummyHandle)] = registerConnection(address -> remote, this, responsibleActorForConnection)
        // TODO: rewrite it using match - case
        if (connectionResult.isDefined) {
          val (localHandle, remoteHandle) = connectionResult.get
          handles ::= localHandle
          responsibleActorForConnection ! ConnectionInitialized(localHandle)
        } else {
          responsibleActorForConnection ! ConnectionFailed(new IllegalArgumentException("Remote address does not reachable"))
        }
      }
    }
  }

  override def toString = "DummyTransport(" + address + ")"
}

object DummyHandle {
  sealed trait HandleState
  case object Limbo extends HandleState
  case object Open extends HandleState
  case object Closed extends HandleState
}

class DummyHandle(val owner: DummyTransportConnector, val localAddress: Address, val remoteAddress: Address, val server: Boolean) extends TransportConnectorHandle(owner.provider) {
  import DummyTransportMedium._
  import DummyHandle._

  @volatile private var _responsibleActor: ActorRef = _
  @volatile var state: HandleState = Limbo

  override def open(responsibleActor: ActorRef) {
    _responsibleActor = responsibleActor
    state = Open
    queue.reverse.foreach { msg ⇒
      dispatchMessage(msg, owner.provider.log) // TODO: Now using the logger of ActorRefProvider, but this is just a hack
    }
  }

  val key = if (server) remoteAddress -> localAddress else localAddress -> remoteAddress
  @volatile var queue = List[RemoteMessage]() // Simulates the internal buffer of the trasport layer -- queues messages until connection accepted

  override def close() {
    state = Closed
    // TODO: Notify other endpoint
    if (owner.isTerminated) throw new IllegalStateException("Cannot close handle: transport already terminated")
    removeConnection(key)
  }

  override def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    if (owner.isTerminated) throw new IllegalStateException("Cannot write to handle: transport already terminated")
    DummyTransportMedium.activityLog ::= SendAttempt(msg, localAddress, remoteAddress)
    handlesForConnection(key) match {
      case Some((clientHandle, serverHandle)) ⇒ {

        val remoteHandle = if (server) clientHandle else serverHandle
        val msgProtocol = createRemoteMessageProtocolBuilder(recipient, msg, senderOption).build

        if (remoteHandle.state == Limbo) {
          queue ::= new RemoteMessage(msgProtocol, remoteHandle.owner.system)
        } else if (remoteHandle.state == Open) {
          remoteHandle.dispatchMessage(new RemoteMessage(msgProtocol, remoteHandle.owner.system), provider.log) // TODO: Now using the logger of ActorRefProvider, but this is just a hack
        }
      }
      case None ⇒ // Error
    }
  }

  // --- Methods lifted shamelessly from RemoteTransport
  private def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol =
    ActorRefProtocol.newBuilder.setPath(actor.path.toStringWithAddress(localAddress)).build

  /**
   * Returns a new RemoteMessageProtocol containing the serialized representation of the given parameters.
   */
  private def createRemoteMessageProtocolBuilder(recipient: ActorRef, message: Any, senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {
    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(toRemoteActorRefProtocol(recipient))
    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    Serialization.currentTransportAddress.withValue(localAddress) {
      messageBuilder.setMessage(MessageSerializer.serialize(owner.system, message.asInstanceOf[AnyRef]))
    }

    messageBuilder
  }
}

case class HostAndPort(host: String, port: Int)

// TODO: make this a class instead of an object - reason: Parallel tests
// Use a system extension
object DummyTransportMedium {
  sealed trait Activity
  case class ConnectionAttempt(link: (Address, Address)) extends Activity
  case class SendAttempt(msg: Any, sender: Address, recipient: Address) extends Activity

  @volatile private var transportTable = Map[HostAndPort, DummyTransportConnector]()
  @volatile private var connectionTable = Map[(HostAndPort, HostAndPort), (DummyHandle, DummyHandle)]()

  @volatile var activityLog = List[Activity]()

  @volatile var droppingSet = Set[Address]()
  @volatile var rejectSet = Set[Address]()
  @volatile var crashSet = Set[Address]()

  def addressToHostAndPort(address: Address) = (address.host, address.port) match {
    case (Some(host), Some(port)) ⇒ HostAndPort(address.host.get, address.port.get)
    case _                        ⇒ throw new IllegalArgumentException("DummyConnector only supports addresses with hostname and port specified")
  }

  def logicalLinkToNetworkLink(link: (Address, Address)): (HostAndPort, HostAndPort) = addressToHostAndPort(link._1) -> addressToHostAndPort(link._2)

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
    transportTable = Map[HostAndPort, DummyTransportConnector]()
    connectionTable = Map[(HostAndPort, HostAndPort), (DummyHandle, DummyHandle)]()
    activityLog = List[Activity]()
  }

  def lookupTransport(address: Address) = transportTable.get(addressToHostAndPort(address))

  def existsTransport(address: Address) = transportTable.contains(addressToHostAndPort(address))

  def registerTransport(address: Address, transport: DummyTransportConnector) {
    transportTable += addressToHostAndPort(address) -> transport
  }

  def isConnected(link: (Address, Address)): Boolean = connectionTable.contains(logicalLinkToNetworkLink(link))

  def registerConnection(link: (Address, Address), clientProvider: DummyTransportConnector, responsibleActor: ActorRef): Option[(DummyHandle, DummyHandle)] = connectionTable.get(logicalLinkToNetworkLink(link)) match {
    case Some(handlePair) ⇒ Some(handlePair)
    case None ⇒ {
      val (address, remote) = link

      // TODO: replace with foreach
      val remoteTransport: Option[DummyTransportConnector] = lookupTransport(remote)
      if (remoteTransport.isDefined) {

        import actmote.TransportConnector.IncomingConnection

        val handlePair = new DummyHandle(clientProvider, address, remote, false) -> new DummyHandle(remoteTransport.get, remote, address, true)
        connectionTable += logicalLinkToNetworkLink(link) -> handlePair
        remoteTransport.get.responsibleActor ! IncomingConnection(handlePair._2)
        Some(handlePair)
      } else {
        None
      }
    }
  }

  def removeConnection(link: (Address, Address)) {
    connectionTable = connectionTable - logicalLinkToNetworkLink(link)
  }

  def handlesForConnection(link: (Address, Address)): Option[(DummyHandle, DummyHandle)] = connectionTable.get(logicalLinkToNetworkLink(link))

}

