/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import akka.actor.{ ActorRef, IllegalActorStateException, simpleName }
import akka.remote._
import RemoteProtocol._
import akka.util._
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroup, ChannelGroupFuture }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.bootstrap.{ ServerBootstrap, ClientBootstrap }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import org.jboss.netty.util.{ TimerTask, Timeout, HashedWheelTimer }
import org.jboss.netty.handler.execution.{ OrderedMemoryAwareThreadPoolExecutor, ExecutionHandler }
import scala.collection.mutable.HashMap
import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic._
import akka.AkkaException
import akka.event.Logging
import locks.ReentrantReadWriteLock
import org.jboss.netty.channel._
import akka.actor.ActorSystemImpl

class RemoteClientMessageBufferException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}

/**
 * This is the abstract baseclass for netty remote clients, currently there's only an
 * ActiveRemoteClient, but others could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
abstract class RemoteClient private[akka] (
  val remoteSupport: NettyRemoteSupport,
  val remoteAddress: RemoteNettyAddress) {

  val log = Logging(remoteSupport.system, "RemoteClient")

  val name = simpleName(this) + "@" + remoteAddress

  private[remote] val runSwitch = new Switch()

  private[remote] def isRunning = runSwitch.isOn

  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean

  def shutdown(): Boolean

  def isBoundTo(address: RemoteNettyAddress): Boolean = remoteAddress == address

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send(message: Any, senderOption: Option[ActorRef], recipient: ActorRef): Unit = if (isRunning) {
    send(remoteSupport.createRemoteMessageProtocolBuilder(recipient, message, senderOption).build)
  } else {
    val exception = new RemoteClientException("RemoteModule client is not running, make sure you have invoked 'RemoteClient.connect()' before using it.", remoteSupport, remoteAddress)
    remoteSupport.notifyListeners(RemoteClientError(exception, remoteSupport, remoteAddress))
    throw exception
  }

  /**
   * Sends the message across the wire
   */
  def send(request: RemoteMessageProtocol): Unit = {
    log.debug("Sending message: {}", new RemoteMessage(request, remoteSupport.system))

    try {
      val payload = remoteSupport.createMessageSendEnvelope(request)
      currentChannel.write(payload).addListener(
        new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            if (future.isCancelled) {
              //Not interesting at the moment
            } else if (!future.isSuccess) {
              remoteSupport.notifyListeners(RemoteClientWriteFailed(payload, future.getCause, remoteSupport, remoteAddress))
            }
          }
        })
    } catch {
      case e: Exception ⇒ remoteSupport.notifyListeners(RemoteClientError(e, remoteSupport, remoteAddress))
    }
  }

  override def toString = name
}

class PassiveRemoteClient(val currentChannel: Channel,
                          remoteSupport: NettyRemoteSupport,
                          remoteAddress: RemoteNettyAddress)
  extends RemoteClient(remoteSupport, remoteAddress) {

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = runSwitch switchOn {
    remoteSupport.notifyListeners(RemoteClientStarted(remoteSupport, remoteAddress))
    log.debug("Starting remote client connection to [{}]", remoteAddress)
  }

  def shutdown() = runSwitch switchOff {
    log.debug("Shutting down remote client [{}]", name)

    remoteSupport.notifyListeners(RemoteClientShutdown(remoteSupport, remoteAddress))
    log.debug("[{}] has been shut down", name)
  }
}

/**
 * RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 */
class ActiveRemoteClient private[akka] (
  remoteSupport: NettyRemoteSupport,
  remoteAddress: RemoteNettyAddress,
  val loader: Option[ClassLoader] = None)
  extends RemoteClient(remoteSupport, remoteAddress) {

  if (remoteAddress.ip.isEmpty) throw new java.net.UnknownHostException(remoteAddress.host)

  import remoteSupport.clientSettings._

  //TODO rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile
  private var bootstrap: ClientBootstrap = _
  @volatile
  private[remote] var connection: ChannelFuture = _
  @volatile
  private[remote] var openChannels: DefaultChannelGroup = _

  @volatile
  private var reconnectionTimeWindowStart = 0L

  def notifyListeners(msg: RemoteLifeCycleEvent): Unit = remoteSupport.notifyListeners(msg)

  def currentChannel = connection.getChannel

  private val senderRemoteAddress = remoteSupport.remote.remoteAddress

  /**
   * Connect to remote server.
   */
  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = {

    def sendSecureCookie(connection: ChannelFuture) {
      val handshake = RemoteControlProtocol.newBuilder.setCommandType(CommandType.CONNECT)
      if (SecureCookie.nonEmpty) handshake.setCookie(SecureCookie.get)
      handshake.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
        .setSystem(senderRemoteAddress.system)
        .setHostname(senderRemoteAddress.transport.host)
        .setPort(senderRemoteAddress.transport.port)
        .build)
      connection.getChannel.write(remoteSupport.createControlEnvelope(handshake.build))
    }

    def closeChannel(connection: ChannelFuture) = {
      val channel = connection.getChannel
      openChannels.remove(channel)
      channel.close()
    }

    def attemptReconnect(): Boolean = {
      log.debug("Remote client reconnecting to [{}]", remoteAddress)
      val connection = bootstrap.connect(new InetSocketAddress(remoteAddress.ip.get, remoteAddress.port))
      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, remoteSupport, remoteAddress))
        false
      } else {
        sendSecureCookie(connection)
        true
      }
    }

    runSwitch switchOn {
      openChannels = new DefaultDisposableChannelGroup(classOf[RemoteClient].getName)

      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, bootstrap, remoteAddress, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      log.debug("Starting remote client connection to [{}]", remoteAddress)

      connection = bootstrap.connect(new InetSocketAddress(remoteAddress.ip.get, remoteAddress.port))

      val channel = connection.awaitUninterruptibly.getChannel
      openChannels.add(channel)

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, remoteSupport, remoteAddress))
        false
      } else {
        sendSecureCookie(connection)
        notifyListeners(RemoteClientStarted(remoteSupport, remoteAddress))
        true
      }
    } match {
      case true ⇒ true
      case false if reconnectIfAlreadyConnected ⇒
        closeChannel(connection)

        log.debug("Remote client reconnecting to [{}]", remoteAddress)
        attemptReconnect()

      case false ⇒ false
    }
  }

  // Please note that this method does _not_ remove the ARC from the NettyRemoteClientModule's map of clients
  def shutdown() = runSwitch switchOff {
    log.debug("Shutting down remote client [{}]", name)

    notifyListeners(RemoteClientShutdown(remoteSupport, remoteAddress))
    openChannels.close.awaitUninterruptibly
    openChannels = null
    bootstrap.releaseExternalResources()
    bootstrap = null
    connection = null

    log.debug("[{}] has been shut down", name)
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = (ReconnectionTimeWindow.toMillis - (System.currentTimeMillis - reconnectionTimeWindowStart)) > 0
      if (timeLeft)
        log.info("Will try to reconnect to remote server for another [{}] milliseconds", timeLeft)

      timeLeft
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

class ActiveRemoteClientPipelineFactory(
  name: String,
  bootstrap: ClientBootstrap,
  remoteAddress: RemoteNettyAddress,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  import client.remoteSupport.clientSettings._

  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(client.remoteSupport.timer, ReadTimeout.length, ReadTimeout.unit)
    val lenDec = new LengthFieldBasedFrameDecoder(MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val remoteClient = new ActiveRemoteClientHandler(name, bootstrap, remoteAddress, client.remoteSupport.timer, client)

    new StaticChannelPipeline(timeout, lenDec, protobufDec, lenPrep, protobufEnc, remoteClient)
  }
}

@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: RemoteNettyAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler {

  def runOnceNow(thunk: ⇒ Unit): Unit = timer.newTimeout(new TimerTask() {
    def run(timeout: Timeout) = try { thunk } finally { timeout.cancel() }
  }, 0, TimeUnit.MILLISECONDS)

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction ⇒
          val rcp = arp.getInstruction
          rcp.getCommandType match {
            case CommandType.SHUTDOWN ⇒ runOnceNow { client.remoteSupport.shutdownClientConnection(remoteAddress) }
            case _                    ⇒ //Ignore others
          }

        case arp: AkkaRemoteProtocol if arp.hasMessage ⇒
          client.remoteSupport.receiveMessage(new RemoteMessage(arp.getMessage, client.remoteSupport.system, client.loader))

        case other ⇒
          throw new RemoteClientException("Unknown message received in remote client handler: " + other, client.remoteSupport, client.remoteAddress)
      }
    } catch {
      case e: Exception ⇒ client.notifyListeners(RemoteClientError(e, client.remoteSupport, client.remoteAddress))
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = client.runSwitch ifOn {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) = {
          if (client.isRunning) {
            client.openChannels.remove(event.getChannel)
            client.connect(reconnectIfAlreadyConnected = true)
          }
        }
      }, client.remoteSupport.clientSettings.ReconnectDelay.toMillis, TimeUnit.MILLISECONDS)
    } else runOnceNow {
      client.remoteSupport.shutdownClientConnection(remoteAddress) // spawn in another thread
    }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    try {
      client.notifyListeners(RemoteClientConnected(client.remoteSupport, client.remoteAddress))
      client.resetReconnectionTimeWindow
    } catch {
      case e: Exception ⇒ client.notifyListeners(RemoteClientError(e, client.remoteSupport, client.remoteAddress))
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.remoteSupport, client.remoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    val cause = event.getCause
    if (cause ne null) {
      client.notifyListeners(RemoteClientError(cause, client.remoteSupport, client.remoteAddress))
      cause match {
        case e: ReadTimeoutException ⇒
          runOnceNow {
            client.remoteSupport.shutdownClientConnection(remoteAddress) // spawn in another thread
          }
        case e: Exception ⇒
          event.getChannel.close() //FIXME Is this the correct behavior???
      }

    } else client.notifyListeners(RemoteClientError(new Exception("Unknown cause"), client.remoteSupport, client.remoteAddress))
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport(_system: ActorSystemImpl, val remote: Remote, val address: RemoteSystemAddress[RemoteNettyAddress])
  extends RemoteSupport[RemoteNettyAddress](_system) with RemoteMarshallingOps {
  val log = Logging(system, "NettyRemoteSupport")

  val serverSettings = remote.remoteSettings.serverSettings
  val clientSettings = remote.remoteSettings.clientSettings

  val timer: HashedWheelTimer = new HashedWheelTimer

  _system.registerOnTermination(timer.stop()) //Shut this guy down at the end

  private val remoteClients = new HashMap[RemoteNettyAddress, RemoteClient]
  private val clientsLock = new ReentrantReadWriteLock

  override protected def useUntrustedMode = serverSettings.UntrustedMode

  protected[akka] def send(
    message: Any,
    senderOption: Option[ActorRef],
    recipient: RemoteActorRef,
    loader: Option[ClassLoader]): Unit = {

    val recipientAddress = recipient.path.address match {
      case RemoteSystemAddress(sys, transport) ⇒
        transport match {
          case x: RemoteNettyAddress ⇒ x
          case _                     ⇒ throw new IllegalArgumentException("invoking NettyRemoteSupport.send with foreign target address " + transport)
        }
    }

    clientsLock.readLock.lock
    try {
      val client = remoteClients.get(recipientAddress) match {
        case Some(client) ⇒ client
        case None ⇒
          clientsLock.readLock.unlock
          clientsLock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(recipientAddress) match {
                //Recheck for addition, race between upgrades
                case Some(client) ⇒ client //If already populated by other writer
                case None ⇒ //Populate map
                  val client = new ActiveRemoteClient(this, recipientAddress, loader)
                  client.connect()
                  remoteClients += recipientAddress -> client
                  client
              }
            } finally {
              clientsLock.readLock.lock
            } //downgrade
          } finally {
            clientsLock.writeLock.unlock
          }
      }
      client.send(message, senderOption, recipient)
    } finally {
      clientsLock.readLock.unlock
    }
  }

  def bindClient(remoteAddress: RemoteNettyAddress, client: RemoteClient, putIfAbsent: Boolean = false): Boolean = {
    clientsLock.writeLock().lock()
    try {
      if (putIfAbsent && remoteClients.contains(remoteAddress)) false
      else {
        client.connect()
        remoteClients.put(remoteAddress, client).foreach(_.shutdown())
        true
      }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def unbindClient(remoteAddress: RemoteNettyAddress): Unit = {
    clientsLock.writeLock().lock()
    try {
      remoteClients.foreach { case (k, v) ⇒ if (v.isBoundTo(remoteAddress)) { v.shutdown(); remoteClients.remove(k) } }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def shutdownClientConnection(remoteAddress: RemoteNettyAddress): Boolean = {
    clientsLock.writeLock().lock()
    try {
      remoteClients.remove(remoteAddress) match {
        case Some(client) ⇒ client.shutdown()
        case None         ⇒ false
      }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def restartClientConnection(remoteAddress: RemoteNettyAddress): Boolean = {
    clientsLock.readLock().lock()
    try {
      remoteClients.get(remoteAddress) match {
        case Some(client) ⇒ client.connect(reconnectIfAlreadyConnected = true)
        case None         ⇒ false
      }
    } finally {
      clientsLock.readLock().unlock()
    }
  }

  /**
   * Server section
   */
  private[akka] val currentServer = new AtomicReference[Option[NettyRemoteServer]](None)

  def name = currentServer.get match {
    case Some(server) ⇒ server.name
    case None         ⇒ remote.remoteAddress.toString
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(loader: Option[ClassLoader] = None): Unit = {
    _isRunning switchOn {
      try {
        currentServer.set(Some(new NettyRemoteServer(this, loader, address)))
      } catch {
        case e: Exception ⇒ notifyListeners(RemoteServerError(e, this))
      }
    }
  }

  /**
   * Common section
   */

  def shutdown(): Unit = _isRunning switchOff {
    clientsLock.writeLock().lock()
    try {
      remoteClients foreach { case (_, client) ⇒ client.shutdown() }
      remoteClients.clear()
    } finally {
      clientsLock.writeLock().unlock()
      currentServer.getAndSet(None) foreach { _.shutdown() }
    }
  }
}

class NettyRemoteServer(
  val remoteSupport: NettyRemoteSupport,
  val loader: Option[ClassLoader],
  val address: RemoteSystemAddress[RemoteNettyAddress]) {
  val log = Logging(remoteSupport.system, "NettyRemoteServer")
  import remoteSupport.serverSettings._

  if (address.transport.ip.isEmpty) throw new java.net.UnknownHostException(address.transport.host)

  val name = "NettyRemoteServer@" + address

  private val factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)

  private val executor = new ExecutionHandler(
    new OrderedMemoryAwareThreadPoolExecutor(
      ExecutionPoolSize,
      MaxChannelMemorySize,
      MaxTotalMemorySize,
      ExecutionPoolKeepAlive.length,
      ExecutionPoolKeepAlive.unit))

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, executor, loader, remoteSupport)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", Backlog)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", ConnectionTimeout.toMillis)

  openChannels.add(bootstrap.bind(new InetSocketAddress(address.transport.ip.get, address.transport.port)))
  remoteSupport.notifyListeners(RemoteServerStarted(remoteSupport))

  def shutdown() {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        b.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(address.system)
          .setHostname(address.transport.host)
          .setPort(address.transport.port)
          .build)
        if (SecureCookie.nonEmpty)
          b.setCookie(SecureCookie.get)
        b.build
      }
      openChannels.write(remoteSupport.createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      executor.releaseExternalResources()
      remoteSupport.notifyListeners(RemoteServerShutdown(remoteSupport))
    } catch {
      case e: Exception ⇒ remoteSupport.notifyListeners(RemoteServerError(e, remoteSupport))
    }
  }
}

class RemoteServerPipelineFactory(
  val name: String,
  val openChannels: ChannelGroup,
  val executor: ExecutionHandler,
  val loader: Option[ClassLoader],
  val remoteSupport: NettyRemoteSupport) extends ChannelPipelineFactory {

  import remoteSupport.serverSettings._

  def getPipeline: ChannelPipeline = {
    val lenDec = new LengthFieldBasedFrameDecoder(MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder

    val authenticator = if (RequireCookie) new RemoteServerAuthenticationHandler(SecureCookie) :: Nil else Nil
    val remoteServer = new RemoteServerHandler(name, openChannels, loader, remoteSupport)
    val stages: List[ChannelHandler] = lenDec :: protobufDec :: lenPrep :: protobufEnc :: executor :: authenticator ::: remoteServer :: Nil
    new StaticChannelPipeline(stages: _*)
  }
}

@ChannelHandler.Sharable
class RemoteServerAuthenticationHandler(secureCookie: Option[String]) extends SimpleChannelUpstreamHandler {
  val authenticated = new AnyRef

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = secureCookie match {
    case None ⇒ ctx.sendUpstream(event)
    case Some(cookie) ⇒
      ctx.getAttachment match {
        case `authenticated` ⇒ ctx.sendUpstream(event)
        case null ⇒ event.getMessage match {
          case remoteProtocol: AkkaRemoteProtocol if remoteProtocol.hasInstruction ⇒
            val instruction = remoteProtocol.getInstruction
            instruction.getCookie match {
              case `cookie` ⇒
                ctx.setAttachment(authenticated)
                ctx.sendUpstream(event)
              case _ ⇒
                throw new SecurityException(
                  "The remote client [" + ctx.getChannel.getRemoteAddress + "] secure cookie is not the same as remote server secure cookie")
            }
          case _ ⇒
            throw new SecurityException("The remote client [" + ctx.getChannel.getRemoteAddress + "] is not authorized!")
        }
      }
  }
}

@ChannelHandler.Sharable
class RemoteServerHandler(
  val name: String,
  val openChannels: ChannelGroup,
  val applicationLoader: Option[ClassLoader],
  val remoteSupport: NettyRemoteSupport) extends SimpleChannelUpstreamHandler {

  val log = Logging(remoteSupport.system, "RemoteServerHandler")

  import remoteSupport.serverSettings._

  //Writes the specified message to the specified channel and propagates write errors to listeners
  private def write(channel: Channel, payload: AkkaRemoteProtocol) {
    channel.write(payload).addListener(
      new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (!future.isCancelled && !future.isSuccess)
            remoteSupport.notifyListeners(RemoteServerWriteFailed(payload, future.getCause, remoteSupport, getClientAddress(channel)))
        }
      })
  }

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx.getChannel)
    remoteSupport.notifyListeners(RemoteServerClientConnected(remoteSupport, clientAddress))
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx.getChannel)
    remoteSupport.notifyListeners(RemoteServerClientDisconnected(remoteSupport, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = getClientAddress(ctx.getChannel) match {
    case s @ Some(address) ⇒
      if (UsePassiveConnections)
        remoteSupport.unbindClient(address)
      remoteSupport.notifyListeners(RemoteServerClientClosed(remoteSupport, s))
    case None ⇒
      remoteSupport.notifyListeners(RemoteServerClientClosed[RemoteNettyAddress](remoteSupport, None))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = try {
    event.getMessage match {
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒
        remoteSupport.receiveMessage(new RemoteMessage(remote.getMessage, remoteSupport.system, applicationLoader))

      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒
        val instruction = remote.getInstruction
        instruction.getCommandType match {
          case CommandType.CONNECT if UsePassiveConnections ⇒
            val origin = instruction.getOrigin
            val inbound = RemoteNettyAddress(origin.getHostname, origin.getPort)
            val client = new PassiveRemoteClient(event.getChannel, remoteSupport, inbound)
            remoteSupport.bindClient(inbound, client)
          case CommandType.SHUTDOWN ⇒ //FIXME Dispose passive connection here, ticket #1410
          case _                    ⇒ //Unknown command
        }
      case _ ⇒ //ignore
    }
  } catch {
    case e: Exception ⇒ remoteSupport.notifyListeners(RemoteServerError(e, remoteSupport))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    remoteSupport.notifyListeners(RemoteServerError(event.getCause, remoteSupport))
    event.getChannel.close()
  }

  private def getClientAddress(c: Channel): Option[RemoteNettyAddress] =
    c.getRemoteAddress match {
      case inet: InetSocketAddress ⇒ Some(RemoteNettyAddress(inet.getHostName, Some(inet.getAddress), inet.getPort))
      case _                       ⇒ None
    }
}

class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
  protected val guard = new ReentrantReadWriteLock
  protected val open = new AtomicBoolean(true)

  override def add(channel: Channel): Boolean = {
    guard.readLock().lock()
    try {
      if (open.get) {
        super.add(channel)
      } else {
        channel.close()
        false
      }
    } finally {
      guard.readLock().unlock()
    }
  }

  override def close(): ChannelGroupFuture = {
    guard.writeLock().lock()
    try {
      if (open.getAndSet(false)) super.close() else throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    } finally {
      guard.writeLock().unlock()
    }
  }
}
