/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import java.net.InetSocketAddress
import org.jboss.netty.util.HashedWheelTimer
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.channel.{ ChannelHandler, StaticChannelPipeline, SimpleChannelUpstreamHandler, MessageEvent, ExceptionEvent, ChannelStateEvent, ChannelPipelineFactory, ChannelPipeline, ChannelHandlerContext, ChannelFuture, Channel }
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.execution.ExecutionHandler
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import akka.remote.RemoteProtocol.{ RemoteControlProtocol, CommandType, AkkaRemoteProtocol }
import akka.remote.{ RemoteProtocol, RemoteMessage, RemoteLifeCycleEvent, RemoteClientStarted, RemoteClientShutdown, RemoteClientException, RemoteClientError, RemoteClientDisconnected, RemoteClientConnected }
import akka.actor.{ simpleName, Address }
import akka.AkkaException
import akka.event.Logging
import akka.util.Switch
import akka.actor.ActorRef
import org.jboss.netty.channel.ChannelFutureListener
import akka.remote.RemoteClientWriteFailed
import java.net.InetAddress
import org.jboss.netty.util.TimerTask
import org.jboss.netty.util.Timeout
import java.util.concurrent.TimeUnit

class RemoteClientMessageBufferException(message: String, cause: Throwable) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null)
}

/**
 * This is the abstract baseclass for netty remote clients, currently there's only an
 * ActiveRemoteClient, but others could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
abstract class RemoteClient private[akka] (
  val netty: NettyRemoteTransport,
  val remoteAddress: Address) {

  val log = Logging(netty.system, "RemoteClient")

  val name = simpleName(this) + "@" + remoteAddress

  private[remote] val runSwitch = new Switch()

  private[remote] def isRunning = runSwitch.isOn

  protected def currentChannel: Channel

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean

  def shutdown(): Boolean

  def isBoundTo(address: Address): Boolean = remoteAddress == address

  /**
   * Converts the message to the wireprotocol and sends the message across the wire
   */
  def send(message: Any, senderOption: Option[ActorRef], recipient: ActorRef): Unit = if (isRunning) {
    if (netty.remoteSettings.LogSend) log.debug("Sending message {} from {} to {}", message, senderOption, recipient)
    send((message, senderOption, recipient))
  } else {
    val exception = new RemoteClientException("RemoteModule client is not running, make sure you have invoked 'RemoteClient.connect()' before using it.", netty, remoteAddress)
    netty.notifyListeners(RemoteClientError(exception, netty, remoteAddress))
    throw exception
  }

  /**
   * Sends the message across the wire
   */
  private def send(request: (Any, Option[ActorRef], ActorRef)): Unit = {
    try {
      val channel = currentChannel
      val f = channel.write(request)
      f.addListener(
        new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            if (future.isCancelled || !future.isSuccess) {
              netty.notifyListeners(RemoteClientWriteFailed(request, future.getCause, netty, remoteAddress))
            }
          }
        })
      // Check if we should back off
      if (!channel.isWritable) {
        val backoff = netty.settings.BackoffTimeout
        if (backoff.length > 0 && !f.await(backoff.length, backoff.unit)) f.cancel() //Waited as long as we could, now back off
      }
    } catch {
      case e: Exception ⇒ netty.notifyListeners(RemoteClientError(e, netty, remoteAddress))
    }
  }

  override def toString = name
}

/**
 * RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 */
class ActiveRemoteClient private[akka] (
  netty: NettyRemoteTransport,
  remoteAddress: Address,
  localAddress: Address)
  extends RemoteClient(netty, remoteAddress) {

  import netty.settings

  //TODO rewrite to a wrapper object (minimize volatile access and maximize encapsulation)
  @volatile
  private var bootstrap: ClientBootstrap = _
  @volatile
  private var connection: ChannelFuture = _
  @volatile
  private[remote] var openChannels: DefaultChannelGroup = _
  @volatile
  private var executionHandler: ExecutionHandler = _

  @volatile
  private var reconnectionTimeWindowStart = 0L

  def notifyListeners(msg: RemoteLifeCycleEvent): Unit = netty.notifyListeners(msg)

  def currentChannel = connection.getChannel

  /**
   * Connect to remote server.
   */
  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = {

    def sendSecureCookie(connection: ChannelFuture) {
      val handshake = RemoteControlProtocol.newBuilder.setCommandType(CommandType.CONNECT)
      if (settings.SecureCookie.nonEmpty) handshake.setCookie(settings.SecureCookie.get)
      handshake.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
        .setSystem(localAddress.system)
        .setHostname(localAddress.host.get)
        .setPort(localAddress.port.get)
        .build)
      connection.getChannel.write(netty.createControlEnvelope(handshake.build))
    }

    def attemptReconnect(): Boolean = {
      val remoteIP = InetAddress.getByName(remoteAddress.host.get)
      log.debug("Remote client reconnecting to [{}|{}]", remoteAddress, remoteIP)
      connection = bootstrap.connect(new InetSocketAddress(remoteIP, remoteAddress.port.get))
      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, netty, remoteAddress))
        false
      } else {
        sendSecureCookie(connection)
        true
      }
    }

    runSwitch switchOn {
      openChannels = new DefaultDisposableChannelGroup(classOf[RemoteClient].getName)

      executionHandler = new ExecutionHandler(netty.executor)

      val b = new ClientBootstrap(netty.clientChannelFactory)
      b.setPipelineFactory(new ActiveRemoteClientPipelineFactory(name, b, executionHandler, remoteAddress, this))
      b.setOption("tcpNoDelay", true)
      b.setOption("keepAlive", true)
      b.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
      bootstrap = b

      val remoteIP = InetAddress.getByName(remoteAddress.host.get)
      log.debug("Starting remote client connection to [{}|{}]", remoteAddress, remoteIP)

      connection = bootstrap.connect(new InetSocketAddress(remoteIP, remoteAddress.port.get))

      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, netty, remoteAddress))
        false
      } else {
        sendSecureCookie(connection)
        notifyListeners(RemoteClientStarted(netty, remoteAddress))
        true
      }
    } match {
      case true ⇒ true
      case false if reconnectIfAlreadyConnected ⇒
        connection.getChannel.close()
        openChannels.remove(connection.getChannel)

        log.debug("Remote client reconnecting to [{}]", remoteAddress)
        attemptReconnect()

      case false ⇒ false
    }
  }

  // Please note that this method does _not_ remove the ARC from the NettyRemoteClientModule's map of clients
  def shutdown() = runSwitch switchOff {
    log.debug("Shutting down remote client [{}]", name)

    notifyListeners(RemoteClientShutdown(netty, remoteAddress))
    try {
      if ((connection ne null) && (connection.getChannel ne null))
        connection.getChannel.close()
    } finally {
      try {
        if (openChannels ne null) openChannels.close.awaitUninterruptibly()
      } finally {
        connection = null
        executionHandler = null
      }
    }

    log.debug("[{}] has been shut down", name)
  }

  private[akka] def isWithinReconnectionTimeWindow: Boolean = {
    if (reconnectionTimeWindowStart == 0L) {
      reconnectionTimeWindowStart = System.currentTimeMillis
      true
    } else {
      val timeLeft = (settings.ReconnectionTimeWindow.toMillis - (System.currentTimeMillis - reconnectionTimeWindowStart)) > 0
      if (timeLeft)
        log.info("Will try to reconnect to remote server for another [{}] milliseconds", timeLeft)

      timeLeft
    }
  }

  private[akka] def resetReconnectionTimeWindow = reconnectionTimeWindowStart = 0L
}

@ChannelHandler.Sharable
class ActiveRemoteClientHandler(
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: Address,
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
            case CommandType.SHUTDOWN ⇒ runOnceNow { client.netty.shutdownClientConnection(remoteAddress) }
            case _                    ⇒ //Ignore others
          }

        case arp: AkkaRemoteProtocol if arp.hasMessage ⇒
          client.netty.receiveMessage(new RemoteMessage(arp.getMessage, client.netty.system))

        case other ⇒
          throw new RemoteClientException("Unknown message received in remote client handler: " + other, client.netty, client.remoteAddress)
      }
    } catch {
      case e: Exception ⇒ client.notifyListeners(RemoteClientError(e, client.netty, client.remoteAddress))
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = client.runSwitch ifOn {
    if (client.isWithinReconnectionTimeWindow) {
      timer.newTimeout(new TimerTask() {
        def run(timeout: Timeout) =
          if (client.isRunning) {
            client.openChannels.remove(event.getChannel)
            client.connect(reconnectIfAlreadyConnected = true)
          }
      }, client.netty.settings.ReconnectDelay.toMillis, TimeUnit.MILLISECONDS)
    } else runOnceNow {
      client.netty.shutdownClientConnection(remoteAddress) // spawn in another thread
    }
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    try {
      client.notifyListeners(RemoteClientConnected(client.netty, client.remoteAddress))
      client.resetReconnectionTimeWindow
    } catch {
      case e: Exception ⇒ client.notifyListeners(RemoteClientError(e, client.netty, client.remoteAddress))
    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    client.notifyListeners(RemoteClientDisconnected(client.netty, client.remoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    val cause = event.getCause
    if (cause ne null) {
      client.notifyListeners(RemoteClientError(cause, client.netty, client.remoteAddress))
      cause match {
        case e: ReadTimeoutException ⇒
          runOnceNow {
            client.netty.shutdownClientConnection(remoteAddress) // spawn in another thread
          }
        case e: Exception ⇒ event.getChannel.close()
      }

    } else client.notifyListeners(RemoteClientError(new Exception("Unknown cause"), client.netty, client.remoteAddress))
  }
}

class ActiveRemoteClientPipelineFactory(
  name: String,
  bootstrap: ClientBootstrap,
  executionHandler: ExecutionHandler,
  remoteAddress: Address,
  client: ActiveRemoteClient) extends ChannelPipelineFactory {

  import client.netty.settings

  def getPipeline: ChannelPipeline = {
    val timeout = new ReadTimeoutHandler(client.netty.timer, settings.ReadTimeout.length, settings.ReadTimeout.unit)
    val lenDec = new LengthFieldBasedFrameDecoder(settings.MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val messageDec = new RemoteMessageDecoder
    val messageEnc = new RemoteMessageEncoder(client.netty)
    val remoteClient = new ActiveRemoteClientHandler(name, bootstrap, remoteAddress, client.netty.timer, client)

    new StaticChannelPipeline(timeout, lenDec, messageDec, lenPrep, messageEnc, executionHandler, remoteClient)
  }
}

class PassiveRemoteClient(val currentChannel: Channel,
                          netty: NettyRemoteTransport,
                          remoteAddress: Address)
  extends RemoteClient(netty, remoteAddress) {

  def connect(reconnectIfAlreadyConnected: Boolean = false): Boolean = runSwitch switchOn {
    netty.notifyListeners(RemoteClientStarted(netty, remoteAddress))
    log.debug("Starting remote client connection to [{}]", remoteAddress)
  }

  def shutdown() = runSwitch switchOff {
    log.debug("Shutting down remote client [{}]", name)

    netty.notifyListeners(RemoteClientShutdown(netty, remoteAddress))
    log.debug("[{}] has been shut down", name)
  }
}

