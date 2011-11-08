/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.netty

import akka.actor.{ ActorRef, IllegalActorStateException, AutoReceivedMessage, simpleName }
import akka.remote._
import RemoteProtocol._
import akka.util._
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroup, ChannelGroupFuture }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.bootstrap.{ ServerBootstrap, ClientBootstrap }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.compression.{ ZlibDecoder, ZlibEncoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import org.jboss.netty.handler.execution.{ OrderedMemoryAwareThreadPoolExecutor, ExecutionHandler }
import org.jboss.netty.util.{ TimerTask, Timeout, HashedWheelTimer }
import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._
import java.net.InetSocketAddress
import java.util.concurrent._
import java.util.concurrent.atomic._
import akka.AkkaException
import akka.AkkaApplication
import akka.dispatch.{ Terminate }

class RemoteClientMessageBufferException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

trait NettyRemoteClientModule extends RemoteClientModule {
  self: RemoteSupport ⇒

  private val remoteClients = new HashMap[RemoteAddress, RemoteClient]
  private val lock = new ReadWriteGuard

  def app: AkkaApplication

  protected[akka] def send(message: Any,
                           senderOption: Option[ActorRef],
                           recipientAddress: InetSocketAddress,
                           recipient: ActorRef,
                           loader: Option[ClassLoader]): Unit =
    withClientFor(recipientAddress, loader) { _.send(message, senderOption, recipient) }

  private[akka] def withClientFor[T](
    address: InetSocketAddress, loader: Option[ClassLoader])(body: RemoteClient ⇒ T): T = {
    val key = RemoteAddress(address)
    lock.readLock.lock
    try {
      val client = remoteClients.get(key) match {
        case Some(client) ⇒ client
        case None ⇒
          lock.readLock.unlock
          lock.writeLock.lock //Lock upgrade, not supported natively
          try {
            try {
              remoteClients.get(key) match {
                //Recheck for addition, race between upgrades
                case Some(client) ⇒ client //If already populated by other writer
                case None ⇒ //Populate map
                  val client = new ActiveRemoteClient(app, self, this, address, loader, self.notifyListeners _)
                  client.connect()
                  remoteClients += key -> client
                  client
              }
            } finally {
              lock.readLock.lock
            } //downgrade
          } finally {
            lock.writeLock.unlock
          }
      }
      body(client)
    } finally {
      lock.readLock.unlock
    }
  }

  def shutdownClientConnection(address: InetSocketAddress): Boolean = lock withWriteGuard {
    remoteClients.remove(RemoteAddress(address)) match {
      case Some(client) ⇒ client.shutdown()
      case None         ⇒ false
    }
  }

  def restartClientConnection(address: InetSocketAddress): Boolean = lock withReadGuard {
    remoteClients.get(RemoteAddress(address)) match {
      case Some(client) ⇒ client.connect(reconnectIfAlreadyConnected = true)
      case None         ⇒ false
    }
  }

  /**
   * Clean-up all open connections.
   */
  def shutdownClientModule() {
    shutdownRemoteClients()
    //TODO: Should we empty our remoteActors too?
    //remoteActors.clear
  }

  def shutdownRemoteClients() = lock withWriteGuard {
    remoteClients foreach { case (_, client) ⇒ client.shutdown() }
    remoteClients.clear()
  }
}

/**
 * This is the abstract baseclass for netty remote clients, currently there's only an
 * ActiveRemoteClient, but others could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
abstract class RemoteClient private[akka] (
  val app: AkkaApplication,
  val remoteSupport: RemoteSupport,
  val module: NettyRemoteClientModule,
  val remoteAddress: InetSocketAddress) extends RemoteMarshallingOps {

  val name = simpleName(this) + "@" +
    remoteAddress.getAddress.getHostAddress + "::" +
    remoteAddress.getPort

  private[remote] val runSwitch = new Switch()

  private[remote] def isRunning = runSwitch.isOn

  protected def notifyListeners(msg: ⇒ Any): Unit

  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean

  def shutdown(): Boolean

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send(message: Any, senderOption: Option[ActorRef], recipient: ActorRef) {
    send(createRemoteMessageProtocolBuilder(Left(recipient), Right(message), senderOption).build)
  }

  /**
   * Sends the message across the wire
   */
  def send(request: RemoteMessageProtocol) {
    if (isRunning) { //TODO FIXME RACY
      app.eventHandler.debug(this, "Sending to connection [%s] message [%s]".format(remoteAddress, new RemoteMessage(request, remoteSupport)))

      // tell
      try {
        val future = currentChannel.write(createMessageSendEnvelope(request))
        future.awaitUninterruptibly() //TODO FIXME SWITCH TO NONBLOCKING WRITE
        if (!future.isCancelled && !future.isSuccess) {
          notifyListeners(RemoteClientWriteFailed(request, future.getCause, module, remoteAddress))
        }
      } catch {
        case e: Exception ⇒ notifyListeners(RemoteClientError(e, module, remoteAddress))
      }
    } else {
      val exception = new RemoteClientException("RemoteModule client is not running, make sure you have invoked 'RemoteClient.connect()' before using it.", module, remoteAddress)
      notifyListeners(RemoteClientError(exception, module, remoteAddress))
      throw exception
    }
  }
}

/**
 *  RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClient private[akka] (
  _app: AkkaApplication,
  remoteSupport: RemoteSupport,
  module: NettyRemoteClientModule,
  remoteAddress: InetSocketAddress,
  val loader: Option[ClassLoader] = None,
  notifyListenersFun: (⇒ Any) ⇒ Unit)
  extends RemoteClient(_app, remoteSupport, module, remoteAddress) {

  val settings = new RemoteClientSettings(app)
  import settings._

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

  def notifyListeners(msg: ⇒ Any): Unit = notifyListenersFun(msg)

  def currentChannel = connection.getChannel

  /**
   * Connect to remote server.
   */
  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = {

    def sendSecureCookie(connection: ChannelFuture) {
      val handshake = RemoteControlProtocol.newBuilder.setCommandType(CommandType.CONNECT)
      if (SECURE_COOKIE.nonEmpty) handshake.setCookie(SECURE_COOKIE.get)
      handshake.setOrigin(RemoteProtocol.AddressProtocol.newBuilder().setHostname(app.hostname).setPort(app.port).build)
      connection.getChannel.write(createControlEnvelope(handshake.build))
    }

    def closeChannel(connection: ChannelFuture) = {
      val channel = connection.getChannel
      openChannels.remove(channel)
      channel.close
    }

    def attemptReconnect(): Boolean = {
      app.eventHandler.debug(this, "Remote client reconnecting to [%s]".format(remoteAddress))

      val connection = bootstrap.connect(remoteAddress)
      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        app.eventHandler.error(connection.getCause, this, "Reconnection to [%s] has failed".format(remoteAddress))
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
      bootstrap.setPipelineFactory(new ActiveRemoteClientPipelineFactory(app, settings, name, bootstrap, remoteAddress, timer, this))
      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("keepAlive", true)

      app.eventHandler.debug(this, "Starting remote client connection to [%s]".format(remoteAddress))

      connection = bootstrap.connect(remoteAddress)

      val channel = connection.awaitUninterruptibly.getChannel
      openChannels.add(channel)

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, module, remoteAddress))
        app.eventHandler.error(connection.getCause, this, "Remote client connection to [%s] has failed".format(remoteAddress))
        false
      } else {
        sendSecureCookie(connection)
        notifyListeners(RemoteClientStarted(module, remoteAddress))
        true
      }
    } match {
      case true ⇒ true
      case false if reconnectIfAlreadyConnected ⇒
        closeChannel(connection)

        app.eventHandler.debug(this, "Remote client reconnecting to [%s]".format(remoteAddress))
        attemptReconnect()

      case false ⇒ false
    }
  }

  // Please note that this method does _not_ remove the ARC from the NettyRemoteClientModule's map of clients
  def shutdown() = runSwitch switchOff {
    app.eventHandler.info(this, "Shutting down remote client [%s]".format(name))

    notifyListeners(RemoteClientShutdown(module, remoteAddress))
    timer.stop()
    timer = null
    openChannels.close.awaitUninterruptibly
    openChannels = null
    bootstrap.releaseExternalResources()
    bootstrap = null
    connection = null

    app.eventHandler.info(this, "[%s] has been shut down".format(name))
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = (RECONNECTION_TIME_WINDOW - (System.currentTimeMillis - reconnectionTimeWindowStart)) > 0
      if (timeLeft) {
        app.eventHandler.info(this, "Will try to reconnect to remote server for another [%s] milliseconds".format(timeLeft))
      }
      timeLeft
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ActiveRemoteClientPipelineFactory(
  app: AkkaApplication,
  val settings: RemoteClientSettings,
  name: String,
  bootstrap: ClientBootstrap,
  remoteAddress: InetSocketAddress,
  timer: HashedWheelTimer,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  import settings._

  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(timer, READ_TIMEOUT.length, READ_TIMEOUT.unit)
    val lenDec = new LengthFieldBasedFrameDecoder(MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder
    val remoteClient = new ActiveRemoteClientHandler(app, settings, name, bootstrap, remoteAddress, timer, client)

    new StaticChannelPipeline(timeout, lenDec, protobufDec, lenPrep, protobufEnc, remoteClient)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val app: AkkaApplication,
  val settings: RemoteClientSettings,
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: InetSocketAddress,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends SimpleChannelUpstreamHandler {

  implicit def _app = app

  def runOnceNow(thunk: ⇒ Unit) = timer.newTimeout(new TimerTask() {
    def run(timeout: Timeout) = try { thunk } finally { timeout.cancel() }
  }, 0, TimeUnit.MILLISECONDS)

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction ⇒
          val rcp = arp.getInstruction
          rcp.getCommandType match {
            case CommandType.SHUTDOWN ⇒ runOnceNow { client.module.shutdownClientConnection(remoteAddress) }
          }

        case arp: AkkaRemoteProtocol if arp.hasMessage ⇒
        //TODO FIXME DOESN'T DO ANYTHING ANYMORE

        case other ⇒
          throw new RemoteClientException("Unknown message received in remote client handler: " + other, client.module, client.remoteAddress)
      }
    } catch {
      case e: Exception ⇒
        app.eventHandler.error(e, this, e.getMessage)
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
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
      }, settings.RECONNECT_DELAY.toMillis, TimeUnit.MILLISECONDS)
    } else runOnceNow {
      client.module.shutdownClientConnection(remoteAddress) // spawn in another thread
    }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    try {
      client.notifyListeners(RemoteClientConnected(client.module, client.remoteAddress))
      app.eventHandler.debug(this, "Remote client connected to [%s]".format(ctx.getChannel.getRemoteAddress))
      client.resetReconnectionTimeWindow
    } catch {
      case e: Exception ⇒
        app.eventHandler.error(e, this, e.getMessage)
        client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.module, client.remoteAddress))
    app.eventHandler.debug(this, "Remote client disconnected from [%s]".format(ctx.getChannel.getRemoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    val cause = event.getCause
    if (cause ne null) {
      app.eventHandler.error(event.getCause, this, "Unexpected exception [%s] from downstream in remote client [%s]".format(event.getCause, event))

      cause match {
        case e: ReadTimeoutException ⇒
          runOnceNow {
            client.module.shutdownClientConnection(remoteAddress) // spawn in another thread
          }
        case e: Exception ⇒
          client.notifyListeners(RemoteClientError(e, client.module, client.remoteAddress))
          event.getChannel.close //FIXME Is this the correct behavior?
      }

    } else app.eventHandler.error(this, "Unexpected exception from downstream in remote client [%s]".format(event))
  }
}

/**
 * Provides the implementation of the Netty remote support
 */
class NettyRemoteSupport(_app: AkkaApplication) extends RemoteSupport(_app) with NettyRemoteServerModule with NettyRemoteClientModule {
  override def toString = name
}

class NettyRemoteServer(val app: AkkaApplication, serverModule: NettyRemoteServerModule, val host: String, val port: Int, val loader: Option[ClassLoader]) extends RemoteMarshallingOps {

  val settings = new RemoteServerSettings(app)
  import settings._

  val name = "NettyRemoteServer@" + host + ":" + port
  val address = new InetSocketAddress(host, port)

  private val factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)

  private val bootstrap = new ServerBootstrap(factory)
  private val executor = new ExecutionHandler(
    new OrderedMemoryAwareThreadPoolExecutor(
      EXECUTION_POOL_SIZE,
      MAX_CHANNEL_MEMORY_SIZE,
      MAX_TOTAL_MEMORY_SIZE,
      EXECUTION_POOL_KEEPALIVE.length,
      EXECUTION_POOL_KEEPALIVE.unit))

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  val pipelineFactory = new RemoteServerPipelineFactory(settings, name, openChannels, executor, loader, serverModule)
  bootstrap.setPipelineFactory(pipelineFactory)
  bootstrap.setOption("backlog", BACKLOG)
  bootstrap.setOption("child.tcpNoDelay", true)
  bootstrap.setOption("child.keepAlive", true)
  bootstrap.setOption("child.reuseAddress", true)
  bootstrap.setOption("child.connectTimeoutMillis", CONNECTION_TIMEOUT.toMillis)

  openChannels.add(bootstrap.bind(address))
  serverModule.notifyListeners(RemoteServerStarted(serverModule))

  def shutdown() {
    app.eventHandler.info(this, "Shutting down remote server [%s]".format(name))
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        if (SECURE_COOKIE.nonEmpty)
          b.setCookie(SECURE_COOKIE.get)
        b.build
      }
      openChannels.write(createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      executor.releaseExternalResources()
      serverModule.notifyListeners(RemoteServerShutdown(serverModule))
    } catch {
      case e: Exception ⇒
        app.eventHandler.error(e, this, e.getMessage)
    }
  }
}

trait NettyRemoteServerModule extends RemoteServerModule {
  self: RemoteSupport ⇒

  def app: AkkaApplication
  def remoteSupport = self

  private[akka] val currentServer = new AtomicReference[Option[NettyRemoteServer]](None)

  def address = currentServer.get match {
    case Some(server) ⇒ server.address
    case None         ⇒ app.defaultAddress
  }

  def name = currentServer.get match {
    case Some(server) ⇒ server.name
    case None         ⇒ "NettyRemoteServer@" + app.hostname + ":" + app.port
  }

  private val _isRunning = new Switch(false)

  def isRunning = _isRunning.isOn

  def start(_hostname: String, _port: Int, loader: Option[ClassLoader] = None): RemoteServerModule = {
    try {
      _isRunning switchOn {
        app.eventHandler.debug(this, "Starting up remote server on [%s:%s]".format(_hostname, _port))

        currentServer.set(Some(new NettyRemoteServer(app, this, _hostname, _port, loader)))
      }
    } catch {
      case e: Exception ⇒
        app.eventHandler.error(e, this, e.getMessage)
        notifyListeners(RemoteServerError(e, this))
    }
    this
  }

  def shutdownServerModule() = _isRunning switchOff {
    currentServer.getAndSet(None) foreach { instance ⇒
      app.eventHandler.debug(this, "Shutting down remote server on %s:%s".format(instance.host, instance.port))
      instance.shutdown()
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteServerPipelineFactory(
  val settings: RemoteServerSettings,
  val name: String,
  val openChannels: ChannelGroup,
  val executor: ExecutionHandler,
  val loader: Option[ClassLoader],
  val server: NettyRemoteServerModule) extends ChannelPipelineFactory {

  import settings._

  def getPipeline: ChannelPipeline = {
    val lenDec = new LengthFieldBasedFrameDecoder(MESSAGE_FRAME_SIZE, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val protobufDec = new ProtobufDecoder(AkkaRemoteProtocol.getDefaultInstance)
    val protobufEnc = new ProtobufEncoder

    val authenticator = if (REQUIRE_COOKIE) new RemoteServerAuthenticationHandler(SECURE_COOKIE) :: Nil else Nil
    val remoteServer = new RemoteServerHandler(settings, name, openChannels, loader, server)
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

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@ChannelHandler.Sharable
class RemoteServerHandler(
  val settings: RemoteServerSettings,
  val name: String,
  val openChannels: ChannelGroup,
  val applicationLoader: Option[ClassLoader],
  val server: NettyRemoteServerModule) extends SimpleChannelUpstreamHandler with RemoteMarshallingOps {

  import settings._

  implicit def app = server.app

  //Writes the specified message to the specified channel and propagates write errors to listeners
  private def write(channel: Channel, payload: AkkaRemoteProtocol) {
    channel.write(payload).addListener(
      new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (future.isCancelled) {
            //Not interesting at the moment
          } else if (!future.isSuccess) {
            val socketAddress = future.getChannel.getRemoteAddress match {
              case i: InetSocketAddress ⇒ Some(i)
              case _                    ⇒ None
            }
            server.notifyListeners(RemoteServerWriteFailed(payload, future.getCause, server, socketAddress))
          }
        }
      })
  }

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    app.eventHandler.debug(this, "Remote client [%s] connected to [%s]".format(clientAddress, server.name))
    server.notifyListeners(RemoteServerClientConnected(server, clientAddress))
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    app.eventHandler.debug(this, "Remote client [%s] disconnected from [%s]".format(clientAddress, server.name))
    server.notifyListeners(RemoteServerClientDisconnected(server, clientAddress))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val clientAddress = getClientAddress(ctx)
    app.eventHandler.debug("Remote client [%s] channel closed from [%s]".format(clientAddress, server.name), this)
    server.notifyListeners(RemoteServerClientClosed(server, clientAddress))
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    event.getMessage match {
      case null ⇒ throw new IllegalActorStateException("Message in remote MessageEvent is null [" + event + "]")
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒ handleRemoteMessageProtocol(remote.getMessage, event.getChannel)
      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒ //Doesn't handle instructions
      case _ ⇒ //ignore
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    app.eventHandler.error(event.getCause, this, "Unexpected exception from remote downstream")
    event.getChannel.close
    server.notifyListeners(RemoteServerError(event.getCause, server))
  }

  private def getClientAddress(ctx: ChannelHandlerContext): Option[InetSocketAddress] =
    ctx.getChannel.getRemoteAddress match {
      case inet: InetSocketAddress ⇒ Some(inet)
      case _                       ⇒ None
    }

  private def handleRemoteMessageProtocol(request: RemoteMessageProtocol, channel: Channel) = try {
    try {
      val remoteMessage = new RemoteMessage(request, server.remoteSupport, applicationLoader)
      val recipient = remoteMessage.recipient

      remoteMessage.payload match {
        case Left(t) ⇒ throw t
        case Right(r) ⇒ r match {
          case _: Terminate ⇒ if (UNTRUSTED_MODE) throw new SecurityException("RemoteModule server is operating is untrusted mode, can not stop the actor") else recipient.stop()
          case _: AutoReceivedMessage if (UNTRUSTED_MODE) ⇒ throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a AutoReceivedMessage to the remote actor")
          case m ⇒ recipient.!(m)(remoteMessage.sender)
        }
      }
    } catch {
      case e: SecurityException ⇒
        app.eventHandler.error(e, this, e.getMessage)
        write(channel, createErrorReplyMessage(e, request))
        server.notifyListeners(RemoteServerError(e, server))
    }
  } catch {
    case e: Exception ⇒
      server.notifyListeners(RemoteServerError(e, server))
      app.eventHandler.error(e, this, e.getMessage)
  }
}

class DefaultDisposableChannelGroup(name: String) extends DefaultChannelGroup(name) {
  protected val guard = new ReadWriteGuard
  protected val open = new AtomicBoolean(true)

  override def add(channel: Channel): Boolean = guard withReadGuard {
    if (open.get) {
      super.add(channel)
    } else {
      channel.close
      false
    }
  }

  override def close(): ChannelGroupFuture = guard withWriteGuard {
    if (open.getAndSet(false)) {
      super.close
    } else {
      throw new IllegalStateException("ChannelGroup already closed, cannot add new channel")
    }
  }
}
