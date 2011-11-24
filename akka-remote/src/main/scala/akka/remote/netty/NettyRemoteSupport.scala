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
import scala.collection.mutable.HashMap
import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic._
import akka.AkkaException
import akka.actor.ActorSystem
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
  val remoteAddress: RemoteAddress) {

  val log = Logging(remoteSupport.system, "RemoteClient")

  val name = simpleName(this) + "@" + remoteAddress

  private[remote] val runSwitch = new Switch()

  private[remote] def isRunning = runSwitch.isOn

  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean

  def shutdown(): Boolean

  def isBoundTo(address: RemoteAddress): Boolean = remoteAddress == address

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send(message: Any, senderOption: Option[ActorRef], recipient: ActorRef): Unit =
    send(remoteSupport.createRemoteMessageProtocolBuilder(Left(recipient), Right(message), senderOption).build)

  /**
   * Sends the message across the wire
   */
  def send(request: RemoteMessageProtocol) {
    if (isRunning) { //TODO FIXME RACY
      log.debug("Sending message: " + new RemoteMessage(request, remoteSupport))

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
    } else {
      val exception = new RemoteClientException("RemoteModule client is not running, make sure you have invoked 'RemoteClient.connect()' before using it.", remoteSupport, remoteAddress)
      remoteSupport.notifyListeners(RemoteClientError(exception, remoteSupport, remoteAddress))
      throw exception
    }
  }

  override def toString = name
}

class PassiveRemoteClient(val currentChannel: Channel,
                          remoteSupport: NettyRemoteSupport,
                          remoteAddress: RemoteAddress)
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
 *  RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClient private[akka] (
  remoteSupport: NettyRemoteSupport,
  remoteAddress: RemoteAddress,
  val loader: Option[ClassLoader] = None)
  extends RemoteClient(remoteSupport, remoteAddress) {

  import remoteSupport.clientSettings._

  //FIXME rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile
  private var bootstrap: ClientBootstrap = _
  @volatile
  private[remote] var connection: ChannelFuture = _
  @volatile
  private[remote] var openChannels: DefaultChannelGroup = _
  @volatile
  private var timer: HashedWheelTimer = _
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
        .setHostname(senderRemoteAddress.hostname)
        .setPort(senderRemoteAddress.port)
        .build)
      connection.getChannel.write(remoteSupport.createControlEnvelope(handshake.build))
    }

    def closeChannel(connection: ChannelFuture) = {
      val channel = connection.getChannel
      openChannels.remove(channel)
      channel.close
    }

    def attemptReconnect(): Boolean = {
      log.debug("Remote client reconnecting to [{}]", remoteAddress)
      val connection = bootstrap.connect(new InetSocketAddress(remoteAddress.hostname, remoteAddress.port))
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
      timer = new HashedWheelTimer

      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      log.debug("Starting remote client connection to [{}]", remoteAddress)

      connection = bootstrap.connect(new InetSocketAddress(remoteAddress.hostname, remoteAddress.port))

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
    timer.stop()
    timer = null
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

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClientPipelineFactory(
  name: String,
  bootstrap: ClientBootstrap,
  remoteAddress: RemoteAddress,
  timer: HashedWheelTimer,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  import client.remoteSupport.clientSettings._

  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(timer, ReadTimeout.length, ReadTimeout.unit)
    val lenDec = new LengthFieldBasedFrameDecoder(MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val remoteClient = new ActiveRemoteClientHandler(name, bootstrap, remoteAddress, timer, client)

    new StaticChannelPipeline(timeout, lenDec, protobufDec, lenPrep, protobufEnc, remoteClient)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: RemoteAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler {

  def runOnceNow(thunk: ⇒ Unit) = timer.newTimeout(new TimerTask() {
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
          client.remoteSupport.receiveMessage(new RemoteMessage(arp.getMessage, client.remoteSupport, client.loader))

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
          event.getChannel.close //FIXME Is this the correct behavior?
      }

    } else client.notifyListeners(RemoteClientError(new Exception("Unknown cause"), client.remoteSupport, client.remoteAddress))
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport(_system: ActorSystem, val remote: Remote) extends RemoteSupport(_system) with RemoteMarshallingOps {
  val log = Logging(system, "NettyRemoteSupport")

  val serverSettings = RemoteExtension(system).settings.serverSettings
  val clientSettings = RemoteExtension(system).settings.clientSettings

  private val remoteClients = new HashMap[RemoteAddress, RemoteClient]
  private val clientsLock = new ReentrantReadWriteLock

  override protected def useUntrustedMode = serverSettings.UntrustedMode

  protected[akka] def send(
    message: Any,
    senderOption: Option[ActorRef],
    recipientAddress: RemoteAddress,
    recipient: ActorRef,
    loader: Option[ClassLoader]): Unit = {

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

  def bindClient(remoteAddress: RemoteAddress, client: RemoteClient, putIfAbsent: Boolean = false): Boolean = {
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

  def unbindClient(remoteAddress: RemoteAddress): Unit = {
    clientsLock.writeLock().lock()
    try {
      remoteClients.foreach { case (k, v) ⇒ if (v.isBoundTo(remoteAddress)) { v.shutdown(); remoteClients.remove(k) } }
    } finally {
      clientsLock.writeLock().unlock()
    }
  }

  def shutdownClientConnection(remoteAddress: RemoteAddress): Boolean = {
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

  def restartClientConnection(remoteAddress: RemoteAddress): Boolean = {
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
    case None         ⇒ "Non-running NettyRemoteServer@" + remote.remoteAddress
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(loader: Option[ClassLoader] = None): Unit = _isRunning switchOn {
    try {
      currentServer.set(Some(new NettyRemoteServer(this, loader)))
    } catch {
      case e: Exception ⇒ notifyListeners(RemoteServerError(e, this))
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

class NettyRemoteServer(val remoteSupport: NettyRemoteSupport, val loader: Option[ClassLoader]) {
  val log = Logging(remoteSupport.system, "NettyRemoteServer")
  import remoteSupport.serverSettings._

  val address = remoteSupport.remote.remoteAddress

  val name = "NettyRemoteServer@" + address

  private val factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(name, openChannels, loader, remoteSupport)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", Backlog)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", ConnectionTimeout.toMillis)

  openChannels.add(bootstrap.bind(new InetSocketAddress(address.hostname, address.port)))
  remoteSupport.notifyListeners(RemoteServerStarted(remoteSupport))

  def shutdown() {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        if (SecureCookie.nonEmpty)
          b.setCookie(SecureCookie.get)
        b.build
      }
      openChannels.write(remoteSupport.createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      remoteSupport.notifyListeners(RemoteServerShutdown(remoteSupport))
    } catch {
      case e: Exception ⇒ remoteSupport.notifyListeners(RemoteServerError(e, remoteSupport))
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(
  val name: String,
  val openChannels: ChannelGroup,
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
    val stages: List[ChannelHandler] = lenDec :: protobufDec :: lenPrep :: protobufEnc :: authenticator ::: remoteServer :: Nil
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

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
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
      remoteSupport.notifyListeners(RemoteServerClientClosed(remoteSupport, None))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = try {
    event.getMessage match {
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒
        remoteSupport.receiveMessage(new RemoteMessage(remote.getMessage, remoteSupport, applicationLoader))

      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒
        val instruction = remote.getInstruction
        instruction.getCommandType match {
          case CommandType.CONNECT if UsePassiveConnections ⇒
            val origin = instruction.getOrigin
            val inbound = RemoteAddress(origin.getHostname, origin.getPort)
            val client = new PassiveRemoteClient(event.getChannel, remoteSupport, inbound)
            remoteSupport.bindClient(inbound, client)
          case CommandType.SHUTDOWN ⇒ //TODO FIXME Dispose passive connection here
          case _                    ⇒ //Unknown command
        }
      case _ ⇒ //ignore
    }
  } catch {
    case e: Exception ⇒ remoteSupport.notifyListeners(RemoteServerError(e, remoteSupport))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    remoteSupport.notifyListeners(RemoteServerError(event.getCause, remoteSupport))
    event.getChannel.close
  }

  private def getClientAddress(c: Channel): Option[RemoteAddress] =
    c.getRemoteAddress match {
      case inet: InetSocketAddress ⇒ Some(RemoteAddress(inet))
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
        channel.close
        false
      }
    } finally {
      guard.readLock().unlock()
    }
  }

  override def close(): ChannelGroupFuture = {
    guard.writeLock().lock()
    try {
      if (open.getAndSet(false)) super.close else throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    } finally {
      guard.writeLock().unlock()
    }
  }
}
