package akka.remote.actmote

import akka.remote._
import actmote.DummyHandle.HandleState
import akka.remote.netty.NettySettings
import akka.actor._
import akka.remote.RemoteProtocol._
import akka.serialization.Serialization
import actmote.TransportConnector._

class DummyTransportConnector(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends TransportConnector(_system, _provider) {

  // TODO: get rid of lock if possible
  val lock = new AnyRef
  val settings = new NettySettings(provider.remoteSettings.config.getConfig("akka.remote.netty"), provider.remoteSettings.systemName)

  @volatile var responsibleActor: ActorRef = _

  private var _isTerminated = false
  private var handles = List[DummyHandle]()

  def isTerminated = lock synchronized {
    _isTerminated && handles.filter(_.state != DummyHandle.Closed).isEmpty
  }

  // Using the fact that system startup uses the caller thread, so different actor systems initializations in the
  // same test will run on the same thread (the test's) and receive the same medium. Other tests, running on different
  // threads will obtain a completely isolated DummyTransportMedium
  val dummyMediumThreadLocal = new ThreadLocal[DummyTransportMedium] {
    override def initialValue() = new DummyTransportMedium()
  }

  def dummyMedium = dummyMediumThreadLocal.get()

  // Access should be synchronized?

  override def listen(responsibleActor: ActorRef) {
    this.responsibleActor = responsibleActor
    responsibleActor ! ConnectorInitialized(address)
  }

  val address = Address("akka", provider.remoteSettings.systemName, settings.Hostname, settings.PortSelector) // Does not handle dynamic ports, used only for testing

  dummyMedium.registerTransport(address, this)

  override def shutdown() {
    // Remove locks if possible
    lock.synchronized {
      if (_isTerminated) throw new IllegalStateException("Cannot shutdown: already terminated")
      //handles foreach { _.close }
      _isTerminated = true
    }
  }

  override def connect(remote: Address, responsibleActorForConnection: ActorRef) {
    if (_isTerminated) throw new IllegalStateException("Cannot connect: already terminated")

    dummyMedium.logConnect(address -> remote)

    // Instead of using different sets, use a map of options and patter matching instead of ifs
    if (dummyMedium.shouldCrash(address)) {
      throw new NullPointerException
    } else if (dummyMedium.shouldReject(address)) {
      responsibleActorForConnection ! ConnectionFailed(new IllegalStateException("Rejected"))
    } else if (!dummyMedium.shouldDrop(address)) {
      dummyMedium.registerConnection(address -> remote, this, responsibleActorForConnection) match {
        case Some((localHandle, remoteHandle)) ⇒ {
          handles ::= localHandle
          responsibleActorForConnection ! ConnectionInitialized(localHandle)
        }
        case None ⇒ responsibleActorForConnection ! ConnectionFailed(new IllegalArgumentException("Remote address does not reachable"))
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

class DummyHandle(val owner: DummyTransportConnector, val localAddress: Address, val remoteAddress: Address, val server: Boolean, val dummyMedium: DummyTransportMedium) extends TransportConnectorHandle(owner.provider) {
  import DummyHandle._

  @volatile private var _responsibleActor: ActorRef = _
  @volatile var state: HandleState = Limbo

  override def open(responsibleActor: ActorRef) {
    _responsibleActor = responsibleActor
    state = Open
    val it = queue.iterator // TODO: there is a race here with write() checking state == Limbo
    while (it.hasNext) dispatchMessage(it.next(), owner.provider.log)
  }

  val key = if (server) remoteAddress -> localAddress else localAddress -> remoteAddress
  val queue = new java.util.concurrent.CopyOnWriteArrayList[RemoteMessage]()

  override def close() {
    state = Closed
    if (owner.isTerminated) throw new IllegalStateException("Cannot close handle: transport already terminated")
    dummyMedium.handlesForConnection(key).foreach {
      case (clientHandle, serverHandle) ⇒
        val remoteHandle = if (server) clientHandle else serverHandle
        remoteHandle._responsibleActor ! Disconnected(remoteHandle)
    }
    dummyMedium.removeConnection(key)
  }

  override def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    if (owner.isTerminated) throw new IllegalStateException("Cannot write to handle: transport already terminated")
    dummyMedium.logSend(msg, localAddress, remoteAddress)
    dummyMedium.handlesForConnection(key) foreach {
      case (clientHandle, serverHandle) ⇒ {

        val remoteHandle = if (server) clientHandle else serverHandle
        val msgProtocol = createRemoteMessageProtocolBuilder(recipient, msg, senderOption).build

        if (remoteHandle.state == Limbo) {
          queue.add(new RemoteMessage(msgProtocol, remoteHandle.owner.system))
        } else if (remoteHandle.state == Open) {
          remoteHandle.dispatchMessage(new RemoteMessage(msgProtocol, remoteHandle.owner.system), provider.log) // TODO: Now using the logger of ActorRefProvider, but this is just a hack
        }
      }
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

object DummyTransportMediumProvider extends ExtensionId[DummyTransportMedium] with ExtensionIdProvider {

  def lookup() = ???

  def createExtension(system: ExtendedActorSystem) = ???

}

object DummyTransportMedium {
  sealed trait Activity
  case class ConnectionAttempt(link: (Address, Address)) extends Activity
  case class SendAttempt(msg: Any, sender: Address, recipient: Address) extends Activity
}

class DummyTransportMedium extends Extension {
  import DummyTransportMedium._

  private val transportTable = new java.util.concurrent.ConcurrentHashMap[HostAndPort, DummyTransportConnector]()
  private val connectionTable = new java.util.concurrent.ConcurrentHashMap[(HostAndPort, HostAndPort), (DummyHandle, DummyHandle)]()

  // Using snapshot consistent collections -- somewhat slow, but this is a testing utility anyway
  private val activityLog = new java.util.concurrent.CopyOnWriteArrayList[Activity]()
  private val droppingSet = new java.util.concurrent.CopyOnWriteArraySet[Address]()
  private val rejectSet = new java.util.concurrent.CopyOnWriteArraySet[Address]()
  private val crashSet = new java.util.concurrent.CopyOnWriteArraySet[Address]()

  def logSend(msg: Any, sender: Address, recipient: Address) {
    activityLog.add(SendAttempt(msg, sender, recipient))
  }

  def logConnect(link: (Address, Address)) {
    activityLog.add(ConnectionAttempt(link))
  }

  def getLogSnapshot: Seq[Activity] = {
    val it = activityLog.iterator() // Take a snapshot iterator -- possible because of CopyOnWriteArrayList
    var result = List[Activity]()
    while (it.hasNext) result ::= it.next()
    result.reverse
  }

  def addressToHostAndPort(address: Address) = (address.host, address.port) match {
    case (Some(host), Some(port)) ⇒ HostAndPort(address.host.get, address.port.get)
    case _                        ⇒ throw new IllegalArgumentException("DummyConnector only supports addresses with hostname and port specified")
  }

  def logicalLinkToNetworkLink(link: (Address, Address)): (HostAndPort, HostAndPort) = addressToHostAndPort(link._1) -> addressToHostAndPort(link._2)

  def silentDrop(source: Address) {
    droppingSet.add(source)
  }

  def shouldDrop(source: Address) = droppingSet.contains(source)

  def crash(source: Address) {
    crashSet.add(source)
  }

  def shouldCrash(source: Address) = crashSet.contains(source)

  def reject(source: Address) {
    rejectSet.add(source)
  }

  def shouldReject(source: Address) = rejectSet.contains(source)

  // Not Atomic!
  def allow(source: Address) {
    droppingSet.remove(source)
    rejectSet.remove(source)
    crashSet.remove(source)
  }

  // Not atomic!
  def clear() {
    transportTable.clear()
    connectionTable.clear()
    activityLog.clear()
    droppingSet.clear()
    rejectSet.clear()
    crashSet.clear()
  }

  def lookupTransport(address: Address) = Option(transportTable.get(addressToHostAndPort(address)))

  def existsTransport(address: Address) = transportTable.contains(addressToHostAndPort(address))

  def registerTransport(address: Address, transport: DummyTransportConnector) {
    transportTable.put(addressToHostAndPort(address), transport)
  }

  def handlesForConnection(link: (Address, Address)): Option[(DummyHandle, DummyHandle)] = Option(connectionTable.get(logicalLinkToNetworkLink(link)))

  def isConnected(link: (Address, Address)): Boolean = connectionTable.contains(logicalLinkToNetworkLink(link))

  // Not atomic! Be careful...
  def registerConnection(link: (Address, Address), clientProvider: DummyTransportConnector, responsibleActor: ActorRef): Option[(DummyHandle, DummyHandle)] = Option(connectionTable.get(logicalLinkToNetworkLink(link))) match {
    case Some(handlePair) ⇒ Some(handlePair)
    case None ⇒ {
      val (address, remote) = link

      val remoteTransport: Option[DummyTransportConnector] = lookupTransport(remote)
      lookupTransport(remote).map { connector ⇒

        val handlePair = new DummyHandle(clientProvider, address, remote, false, this) -> new DummyHandle(remoteTransport.get, remote, address, true, this)
        connectionTable.put(logicalLinkToNetworkLink(link), handlePair)
        remoteTransport.get.responsibleActor ! IncomingConnection(handlePair._2)
        handlePair
      }
    }
  }

  def removeConnection(link: (Address, Address)) {
    connectionTable.remove(logicalLinkToNetworkLink(link))
  }

}

