/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import java.util.concurrent.TimeUnit
import java.net.{ InetAddress, InetSocketAddress }
import org.jboss.netty.util.{ Timeout, TimerTask, HashedWheelTimer }
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.channel.{ ChannelFutureListener, ChannelHandler, StaticChannelPipeline, MessageEvent, ExceptionEvent, ChannelStateEvent, ChannelPipelineFactory, ChannelPipeline, ChannelHandlerContext, ChannelFuture, Channel }
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.execution.ExecutionHandler
import org.jboss.netty.handler.timeout.{ IdleState, IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import akka.remote.RemoteProtocol.{ RemoteControlProtocol, CommandType, AkkaRemoteProtocol }
import akka.remote.{ RemoteProtocol, RemoteMessage, RemoteLifeCycleEvent, RemoteClientStarted, RemoteClientShutdown, RemoteClientException, RemoteClientError, RemoteClientDisconnected, RemoteClientConnected }
import akka.AkkaException
import akka.event.Logging
import akka.actor.{ DeadLetter, Address, ActorRef }
import akka.util.{ NonFatal, Switch }

/**
 * This is the abstract baseclass for netty remote clients, currently there's only an
 * ActiveRemoteClient, but others could be feasible, like a PassiveRemoteClient that
 * reuses an already established connection.
 */
private[akka] abstract class RemoteClient private[akka] (val netty: NettyRemoteTransport, val remoteAddress: Address) {

  val log = Logging(netty.system, "RemoteClient")

  val name = Logging.simpleName(this) + "@" + remoteAddress

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
    if (netty.provider.remoteSettings.LogSend) log.debug("Sending message {} from {} to {}", message, senderOption, recipient)
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
              // We don't call notifyListeners here since we don't think failed message deliveries are errors
              // If the connection goes down we'll get the error reporting done by the pipeline.
              val (message, sender, recipient) = request
              netty.system.deadLetters ! DeadLetter(message, sender.getOrElse(netty.system.deadLetters), recipient)
            }
          }
        })
      // Check if we should back off
      if (!channel.isWritable) {
        val backoff = netty.settings.BackoffTimeout
        if (backoff.length > 0 && !f.await(backoff.length, backoff.unit)) f.cancel() //Waited as long as we could, now back off
      }
    } catch {
      case NonFatal(e) ⇒ netty.notifyListeners(RemoteClientError(e, netty, remoteAddress))
    }
  }

  override def toString: String = name
}

/**
 * RemoteClient represents a connection to an Akka node. Is used to send messages to remote actors on the node.
 */
private[akka] class ActiveRemoteClient private[akka] (
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

      val b = new ClientBootstrap(netty.clientChannelFactory)
      b.setPipelineFactory(netty.createPipeline(new ActiveRemoteClientHandler(name, b, remoteAddress, localAddress, netty.timer, this), true))
      b.setOption("tcpNoDelay", true)
      b.setOption("keepAlive", true)
      b.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
      settings.ReceiveBufferSize.foreach(sz ⇒ b.setOption("receiveBufferSize", sz))
      settings.SendBufferSize.foreach(sz ⇒ b.setOption("sendBufferSize", sz))
      settings.WriteBufferHighWaterMark.foreach(sz ⇒ b.setOption("writeBufferHighWaterMark", sz))
      settings.WriteBufferLowWaterMark.foreach(sz ⇒ b.setOption("writeBufferLowWaterMark", sz))
      settings.OutboundLocalAddress.foreach(s ⇒ b.setOption("localAddress", new InetSocketAddress(s, 0)))
      bootstrap = b

      val remoteIP = InetAddress.getByName(remoteAddress.host.get)
      log.debug("Starting remote client connection to [{}|{}]", remoteAddress, remoteIP)

      connection = bootstrap.connect(new InetSocketAddress(remoteIP, remoteAddress.port.get))

      openChannels.add(connection.awaitUninterruptibly.getChannel) // Wait until the connection attempt succeeds or fails.

      if (!connection.isSuccess) {
        notifyListeners(RemoteClientError(connection.getCause, netty, remoteAddress))
        false
      } else {
        ChannelAddress.set(connection.getChannel, Some(remoteAddress))
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
      if ((connection ne null) && (connection.getChannel ne null)) {
        ChannelAddress.remove(connection.getChannel)
        connection.getChannel.close()
      }
    } finally {
      try {
        if (openChannels ne null) openChannels.close.awaitUninterruptibly()
      } finally {
        connection = null
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
private[akka] class ActiveRemoteClientHandler(
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: Address,
  val localAddress: Address,
  val timer: HashedWheelTimer,
  val client: ActiveRemoteClient)
  extends IdleStateAwareChannelHandler {

  def runOnceNow(thunk: ⇒ Unit): Unit = timer.newTimeout(new TimerTask() {
    def run(timeout: Timeout) = try { thunk } finally { timeout.cancel() }
  }, 0, TimeUnit.MILLISECONDS)

  override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
    import IdleState._

    def createHeartBeat(localAddress: Address, cookie: Option[String]): AkkaRemoteProtocol = {
      val beat = RemoteControlProtocol.newBuilder.setCommandType(CommandType.HEARTBEAT)
      if (cookie.nonEmpty) beat.setCookie(cookie.get)

      client.netty.createControlEnvelope(
        beat.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(localAddress.system)
          .setHostname(localAddress.host.get)
          .setPort(localAddress.port.get)
          .build).build)
    }

    e.getState match {
      case READER_IDLE | ALL_IDLE ⇒ runOnceNow { client.netty.shutdownClientConnection(remoteAddress) }
      case WRITER_IDLE            ⇒ e.getChannel.write(createHeartBeat(localAddress, client.netty.settings.SecureCookie))
    }
  }

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
    val cause = if (event.getCause ne null) event.getCause else new Exception("Unknown cause")
    client.notifyListeners(RemoteClientError(cause, client.netty, client.remoteAddress))
    event.getChannel.close()
  }
}

private[akka] class PassiveRemoteClient(val currentChannel: Channel,
                                        netty: NettyRemoteTransport,
                                        remoteAddress: Address) extends RemoteClient(netty, remoteAddress) {

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

