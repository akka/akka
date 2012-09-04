package akka.remote.actmote

import akka.actor._
import akka.remote._
import actmote.TransportConnector._
import akka.remote.netty._
import org.jboss.netty.util._
import org.jboss.netty.channel._
import akka.event.Logging
import group.ChannelGroup
import org.jboss.netty.handler.timeout._
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.execution.{ OrderedMemoryAwareThreadPoolExecutor, ExecutionHandler }
import com.google.protobuf.MessageLite
import org.jboss.netty.handler.codec.protobuf.ProtobufEncoder
import java.net.{ InetAddress, InetSocketAddress }
import org.jboss.netty.bootstrap.{ ClientBootstrap, ServerBootstrap }
import akka.remote.RemoteProtocol._
import socket.nio.{ NioClientSocketChannelFactory, NioServerSocketChannelFactory }
import java.util.concurrent.{ TimeUnit, Executors }
import util.control.NonFatal
import scala.Some
import akka.actor.DeadLetter
import org.jboss.netty.handler.ssl.SslHandler

private[akka] object ChannelHandle extends ChannelLocal[Option[NettyConnectorHandle]] {
  override def initialValue(channel: Channel) = None
}

class ConnectionCancelledException(msg: String) extends Exception(msg)

class NettyConnector(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends TransportConnector(_system, _provider) with MessageEncodings {
  @volatile var responsibleActor: ActorRef = _
  @volatile var address: Address = _

  private[akka] val settings = new NettySettings(provider.remoteSettings.config.getConfig("akka.remote.netty"), provider.remoteSettings.systemName)

  // TODO replace by system.scheduler
  private val timer: HashedWheelTimer = new HashedWheelTimer(system.threadFactory)
  private lazy val log = Logging(system.eventStream, "NettyConnector")
  /**
   * Backing scaffolding for the default implementation of NettyRemoteSupport.createPipeline.
   */
  object PipelineFactory {
    /**
     * Construct a DefaultChannelPipeline from a sequence of handlers; to be used
     * in implementations of ChannelPipelineFactory.
     */
    def apply(handlers: Seq[ChannelHandler]): DefaultChannelPipeline =
      (new DefaultChannelPipeline /: handlers) { (p, h) ⇒ p.addLast(Logging.simpleName(h.getClass), h); p }

    /**
     * Constructs the NettyRemoteTransport default pipeline with the give “head” handler, which
     * is taken by-name to allow it not to be shared across pipelines.
     *
     * @param withTimeout determines whether an IdleStateHandler shall be included
     */
    def apply(endpoint: ⇒ Seq[ChannelHandler], withTimeout: Boolean, isClient: Boolean): ChannelPipelineFactory =
      new ChannelPipelineFactory { override def getPipeline = apply(defaultStack(withTimeout, isClient) ++ endpoint) }

    /**
     * Construct a default protocol stack, excluding the “head” handler (i.e. the one which
     * actually dispatches the received messages to the local target actors).
     */
    def defaultStack(withTimeout: Boolean, isClient: Boolean): Seq[ChannelHandler] =
      (if (settings.EnableSSL) List(NettySSLSupport(settings, NettyConnector.this.log, isClient)) else Nil) :::
        (if (withTimeout) List(timeout) else Nil) :::
        msgFormat :::
        authenticator :::
        executionHandler

    /**
     * Construct an IdleStateHandler which uses [[akka.remote.netty.NettyRemoteTransport]].timer.
     */
    def timeout = new IdleStateHandler(timer,
      settings.ReadTimeout.toSeconds.toInt,
      settings.WriteTimeout.toSeconds.toInt,
      settings.AllTimeout.toSeconds.toInt)

    private[akka] class RemoteMessageEncoder(encodingSupport: MessageEncodings) extends ProtobufEncoder {
      override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef): AnyRef = {
        msg match {
          case (message: Any, sender: Option[_], recipient: ActorRef) ⇒
            super.encode(ctx, channel,
              encodingSupport.createMessageSendEnvelope(
                encodingSupport.createRemoteMessageProtocolBuilder(
                  recipient,
                  message,
                  sender.asInstanceOf[Option[ActorRef]]).build))
          case _ ⇒ super.encode(ctx, channel, msg)
        }
      }
    }

    /**
     * Construct frame&protobuf encoder/decoder.
     */
    def msgFormat = new LengthFieldBasedFrameDecoder(settings.MessageFrameSize, 0, 4, 0, 4) ::
      new LengthFieldPrepender(4) ::
      new RemoteMessageDecoder ::
      new RemoteMessageEncoder(NettyConnector.this) ::
      Nil

    /**
     * Construct an ExecutionHandler which is used to ensure that message dispatch does not
     * happen on a netty thread (that could be bad if re-sending over the network for
     * remote-deployed actors).
     */
    val executionHandler = if (settings.ExecutionPoolSize != 0)
      List(new ExecutionHandler(new OrderedMemoryAwareThreadPoolExecutor(
        settings.ExecutionPoolSize,
        settings.MaxChannelMemorySize,
        settings.MaxTotalMemorySize,
        settings.ExecutionPoolKeepalive.length,
        settings.ExecutionPoolKeepalive.unit,
        AkkaProtocolMessageSizeEstimator,
        system.threadFactory)))
    else Nil

    /**
     * Helps keep track of how many bytes are in flight
     */
    object AkkaProtocolMessageSizeEstimator extends DefaultObjectSizeEstimator {
      override final def estimateSize(o: AnyRef): Int =
        o match {
          case proto: MessageLite ⇒
            val msgSize = proto.getSerializedSize
            val misalignment = msgSize % 8
            if (misalignment != 0) msgSize + 8 - misalignment else msgSize
          case msg ⇒ super.estimateSize(msg)
        }
    }

    /**
     * Construct and authentication handler which uses the SecureCookie to somewhat
     * protect the TCP port from unauthorized use (don’t rely on it too much, though,
     * as this is NOT a cryptographic feature).
     */
    def authenticator = if (settings.RequireCookie) List(new RemoteServerAuthenticationHandler(settings.SecureCookie)) else Nil
  }

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")
  @volatile private var channel: Channel = _

  val ip = InetAddress.getByName(settings.Hostname)

  private val channelFactory =
    settings.UseDispatcherForIO match {
      case Some(id) ⇒
        val d = system.dispatchers.lookup(id)
        new NioServerSocketChannelFactory(d, d)
      case None ⇒
        new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool())
    }

  private val clientChannelFactory = settings.UseDispatcherForIO match {
    case Some(id) ⇒
      val d = system.dispatchers.lookup(id)
      new NioClientSocketChannelFactory(d, d)
    case None ⇒
      new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool())
  }

  private val serverBootstrap = {
    val b = new ServerBootstrap(channelFactory)
    b.setPipelineFactory(PipelineFactory(Seq(new NettyConnectorServerHandler(openChannels, this)), withTimeout = false, isClient = false))
    b.setOption("backlog", settings.Backlog)
    b.setOption("tcpNoDelay", true)
    b.setOption("child.keepAlive", true)
    b.setOption("reuseAddress", true)
    settings.ReceiveBufferSize.foreach(sz ⇒ b.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz ⇒ b.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz ⇒ b.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz ⇒ b.setOption("writeBufferLowWaterMark", sz))
    b
  }

  private def clientBootstrap(name: String, localAddress: Address, remoteAddress: Address) = {
    val b = new ClientBootstrap(clientChannelFactory)
    // TODO: is it valid to reuse the client and server timers?
    b.setPipelineFactory(PipelineFactory(Seq(new ActiveRemoteClientHandler(name, b, remoteAddress, localAddress, timer, this, null)), withTimeout = true, isClient = true))
    b.setOption("tcpNoDelay", true)
    b.setOption("keepAlive", true)
    b.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
    settings.ReceiveBufferSize.foreach(sz ⇒ b.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz ⇒ b.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz ⇒ b.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz ⇒ b.setOption("writeBufferLowWaterMark", sz))
    settings.OutboundLocalAddress.foreach(s ⇒ b.setOption("localAddress", new InetSocketAddress(s, 0)))
    b
  }

  private[akka] def setAddressFromChannel(ch: Channel) {
    val addr = ch.getLocalAddress match {
      case sa: InetSocketAddress ⇒ sa
      case x                     ⇒ throw new RemoteTransportException("unknown local address type " + x.getClass, null)
    }
    address = Address("akka", provider.remoteSettings.systemName, settings.Hostname, addr.getPort)
  }

  private def startup() {
    channel = serverBootstrap.bind(new InetSocketAddress(ip, settings.PortSelector))
    openChannels.add(channel)
    setAddressFromChannel(channel)
  }

  def listen(responsibleActor: ActorRef) {
    this.responsibleActor = responsibleActor
    try {
      startup()
      responsibleActor ! ConnectorInitialized(address)
    } catch {
      case NonFatal(e) ⇒ responsibleActor ! ConnectorFailed(e)
    }
  }

  def sendSecureCookie(channel: Channel): ChannelFuture = {
    val handshake = RemoteControlProtocol.newBuilder.setCommandType(CommandType.CONNECT)
    if (settings.SecureCookie.nonEmpty) handshake.setCookie(settings.SecureCookie.get)
    handshake.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
      .setSystem(address.system)
      .setHostname(address.host.get)
      .setPort(address.port.get)
      .build)
    channel.write(createControlEnvelope(handshake.build))
  }

  private class ConnectionFinalStepListener(responsibleActorForConnection: ActorRef) extends ChannelFutureListener {
    def operationComplete(future: ChannelFuture) {
      if (!future.isSuccess) {
        responsibleActorForConnection ! ConnectionFailed(future.getCause)
      } else if (future.isCancelled) {
        responsibleActorForConnection ! ConnectionFailed(new ConnectionCancelledException("Connection was cancelled during sending the secure cookie"))
      } else {
        val handle = new NettyConnectorHandle(provider, NettyConnector.this, future.getChannel)
        ChannelHandle.set(future.getChannel, Some(handle))
        responsibleActorForConnection ! ConnectionInitialized(handle)
      }
    }
  }

  def connect(remoteAddress: Address, responsibleActorForConnection: ActorRef) {
    val name = Logging.simpleName(this) + "@" + remoteAddress
    val remoteIP = InetAddress.getByName(remoteAddress.host.get)
    val remotePort = remoteAddress.port.get
    val connectionFuture = clientBootstrap(name, address, remoteAddress).connect(new InetSocketAddress(remoteIP, remotePort))

    connectionFuture.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture) {
        if (!future.isSuccess) {
          responsibleActorForConnection ! ConnectionFailed(future.getCause)
        } else if (future.isCancelled) {
          responsibleActorForConnection ! ConnectionFailed(new ConnectionCancelledException("Connection was cancelled: " + name))
        } else {
          if (settings.EnableSSL) {
            connectionFuture.getChannel.getPipeline.get[SslHandler](classOf[SslHandler]).handshake().addListener(new ChannelFutureListener {
              def operationComplete(future: ChannelFuture) {
                if (!future.isSuccess) {
                  responsibleActorForConnection ! ConnectionFailed(future.getCause)
                } else if (future.isCancelled) {
                  responsibleActorForConnection ! ConnectionFailed(new ConnectionCancelledException("Connection was cancelled during SSL handshake: " + name))
                } else {
                  sendSecureCookie(future.getChannel)
                }
              }
            })
          } else {
            sendSecureCookie(future.getChannel).addListener(new ConnectionFinalStepListener(responsibleActorForConnection))
          }
        }
      }
    })
  }

  def shutdown() {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        b.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(address.system)
          .setHostname(address.host.get)
          .setPort(address.port.get)
          .build)
        if (settings.SecureCookie.nonEmpty)
          b.setCookie(settings.SecureCookie.get)
        b.build
      }
      openChannels.write(createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      serverBootstrap.releaseExternalResources()
      //TODO: this is the task of the ActorManagedRemotes
      //netty.notifyListeners(RemoteServerShutdown(netty))
    } catch {
      //TODO: this is the task of the ActorManagedRemotes
      case e: Exception ⇒ //netty.notifyListeners(RemoteServerError(e, netty))
    }
  }
}

@ChannelHandler.Sharable
private[akka] class NettyConnectorServerHandler(
  val openChannels: ChannelGroup,
  val connector: NettyConnector) extends SimpleChannelUpstreamHandler {

  //import netty.settings

  //WARNING!! Removed setting the address from here -- check Server for original code

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  // TODO might want to log or otherwise signal that a TCP connection has been established here.
  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = ()

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    // TODO disconnects are not handled yet in ActorManagedRemoting
    //netty.notifyListeners(RemoteServerClientDisconnected(netty, ChannelAddress.get(ctx.getChannel)))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    // TODO disconnects are not handled yet in ActorManagedRemoting

    //val address = ChannelAddress.get(ctx.getChannel)
    //if (address.isDefined && settings.UsePassiveConnections)
    //  netty.unbindClient(address.get)

    //netty.notifyListeners(RemoteServerClientClosed(netty, address))
    //ChannelAddress.remove(ctx.getChannel)
    ChannelHandle.remove(ctx.getChannel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = try {
    event.getMessage match {
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒ ChannelHandle.get(event.getChannel) match {
        // TODO: refactor notifying handle owner
        case Some(handle) ⇒ handle.dispatchMessage(new RemoteMessage(remote.getMessage, connector.system), handle.provider.log) // TODO: Using the logger of ActorRefProvider -- this is just a hack
        case None         ⇒ //TODO: signal and error and terminate the connection
      }

      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒
        val instruction = remote.getInstruction
        instruction.getCommandType match {
          // TODO: What to do if CONNECT is received twice??
          case CommandType.CONNECT ⇒
            val origin = instruction.getOrigin
            val inbound = Address("akka", origin.getSystem, origin.getHostname, origin.getPort)
            val handle = new NettyConnectorHandle(connector.provider, connector, event.getChannel)

            //ChannelAddress.set(event.getChannel, Option(inbound))
            ChannelHandle.set(event.getChannel, Some(handle))
            connector.responsibleActor ! IncomingConnection(handle)
          //TODO: Block the Netty pipeline -- Update, use Channel.setReadable in channelOpen
          //TODO: Document that the threadpool should contain enough threads
          //Thread.sleep(500) // TODO: just for trying
          //netty.bindClient(inbound, new PassiveRemoteClient(event.getChannel, netty, inbound))

          //netty.notifyListeners(RemoteServerClientConnected(netty, Option(inbound)))
          case CommandType.SHUTDOWN  ⇒ //Will be unbound in channelClosed
          case CommandType.HEARTBEAT ⇒ //Other guy is still alive
          case _                     ⇒ //Unknown command
        }
      case _ ⇒ //ignore
    }
  } catch {
    case e: Exception ⇒ {
      // TODO: Notify endpoint is handle is registered for the Channel. Otherwise notify headActor
      //netty.notifyListeners(RemoteServerError(e, netty))
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    // TODO: Notify ActorManagedRemoting endpoint
    //netty.notifyListeners(RemoteServerError(event.getCause, netty))
    event.getChannel.close()
  }
}

@ChannelHandler.Sharable
private[akka] class ActiveRemoteClientHandler(
  val name: String,
  val bootstrap: ClientBootstrap,
  val remoteAddress: Address,
  val localAddress: Address,
  val timer: HashedWheelTimer,
  val connector: NettyConnector,
  val handle: NettyConnectorHandle) //TODO: handle should be stored in ChannelLocal?
  extends IdleStateAwareChannelHandler {

  def runOnceNow(thunk: ⇒ Unit): Unit = timer.newTimeout(new TimerTask() {
    def run(timeout: Timeout) = try { thunk } finally { timeout.cancel() }
  }, 0, TimeUnit.MILLISECONDS)

  override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
    import IdleState._

    def createHeartBeat(localAddress: Address, cookie: Option[String]): AkkaRemoteProtocol = {
      val beat = RemoteControlProtocol.newBuilder.setCommandType(CommandType.HEARTBEAT)
      if (cookie.nonEmpty) beat.setCookie(cookie.get)

      connector.createControlEnvelope(
        beat.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(localAddress.system)
          .setHostname(localAddress.host.get)
          .setPort(localAddress.port.get)
          .build).build)
    }

    e.getState match {
      //TODO: Notify endpoint actor of shutting down
      case READER_IDLE | ALL_IDLE ⇒ //runOnceNow { }
      case WRITER_IDLE            ⇒ e.getChannel.write(createHeartBeat(localAddress, connector.settings.SecureCookie))
    }
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) {
    try {
      event.getMessage match {
        case arp: AkkaRemoteProtocol if arp.hasInstruction ⇒
          val rcp = arp.getInstruction
          rcp.getCommandType match {
            //TODO: Notify endpoint actor of shutting down
            case CommandType.SHUTDOWN ⇒ // runOnceNow { }
            case _                    ⇒ //Ignore others
          }

        case arp: AkkaRemoteProtocol if arp.hasMessage ⇒
          handle.dispatchMessage(new RemoteMessage(arp.getMessage, connector.system), handle.provider.log) // TODO: Using the logger of ActorRefProvider -- this is just a hack
        //client.netty.receiveMessage(new RemoteMessage(arp.getMessage, client.netty.system))

        case other ⇒
        //TODO: this exception should be thrown in ActorManagedRemoting
        //throw new RemoteClientException("Unknown message received in remoteAddress client handler: " + other, client.netty, client.remoteAddress)
      }
    } catch {
      case e: Exception ⇒ //TODO: notify endpoint of error
      //client.notifyListeners(RemoteClientError(e, client.netty, client.remoteAddress))
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) {
    ChannelHandle.remove(event.getChannel)
    // TODO: notify endpoint
  }

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) {
    //    try {
    //      client.notifyListeners(RemoteClientConnected(client.netty, client.remoteAddress))
    //      client.resetReconnectionTimeWindow
    //    } catch {
    //      case e: Exception ⇒ client.notifyListeners(RemoteClientError(e, client.netty, client.remoteAddress))
    //    }
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) {
    //    client.notifyListeners(RemoteClientDisconnected(client.netty, client.remoteAddress))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) {
    //    val cause = if (event.getCause ne null) event.getCause else new Exception("Unknown cause")
    //    client.notifyListeners(RemoteClientError(cause, client.netty, client.remoteAddress))
    //    event.getChannel.close()
  }
}

class NettyConnectorHandle(provider: RemoteActorRefProvider, connector: NettyConnector, channel: Channel) extends TransportConnectorHandle(provider) {
  @volatile var responsibleActor: ActorRef = _
  override def remoteAddress = null // TODO:
  override def localAddress = provider.transport.address // TODO: ugly, just receive from the Connector instead

  override def open(responsibleActor: ActorRef) {
    this.responsibleActor = responsibleActor
    // TODO: use Channel.setReadable() magic
  }

  override def close() {
    // TODO: channel is removed from channelgroup?
    ChannelHandle.remove(channel)
    channel.close()
  }

  // TODO: document dropping policy
  override def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) = {
    import connector.system.deadLetters
    try {
      val request = (msg, senderOption, recipient)
      if (!channel.isWritable) {
        //deadLetters ! DeadLetter(msg, senderOption.getOrElse(deadLetters), recipient)
        false // TODO: correctly implement backoff (now just returning false to make compiler happy)
      } else {
        val f = channel.write(request)
        f.addListener(
          new ChannelFutureListener {

            def operationComplete(future: ChannelFuture): Unit =
              if (future.isCancelled || !future.isSuccess) request match {
                case (msg, sender, recipient) ⇒ deadLetters ! DeadLetter(msg, sender.getOrElse(deadLetters), recipient)
                // We don't call notifyListeners here since we don't think failed message deliveries are errors
                /// If the connection goes down we'll get the error reporting done by the pipeline.
              }
          })
        true
      }
    } catch {
      case NonFatal(e) ⇒ true // TODO: just to make the compiler happy, must be considered carefully
      // TODO: signal error to enpoint actor
      //netty.notifyListeners(RemoteClientError(e, netty, remoteAddress))
    }
  }
}
