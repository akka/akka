/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.transport.netty

import akka.actor.{ Address, ExtendedActorSystem }
import akka.dispatch.ThreadPoolConfig
import akka.event.Logging
import akka.remote.transport.AssociationHandle.HandleEventListener
import akka.remote.transport.Transport._
import akka.remote.transport.netty.NettyTransportSettings.{ Udp, Tcp, Mode }
import akka.remote.transport.{ AssociationHandle, Transport }
import akka.{ OnlyCauseStackTrace, ConfigurationException }
import com.typesafe.config.Config
import java.net.{ UnknownHostException, SocketAddress, InetAddress, InetSocketAddress, ConnectException }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, Executors, CancellationException }
import org.jboss.netty.bootstrap.{ ConnectionlessBootstrap, Bootstrap, ClientBootstrap, ServerBootstrap }
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroup, ChannelGroupFuture, ChannelGroupFutureListener }
import org.jboss.netty.channel.socket.nio.{ NioDatagramChannelFactory, NioServerSocketChannelFactory, NioClientSocketChannelFactory }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.ssl.SslHandler
import scala.concurrent.duration.{ Duration, FiniteDuration, MILLISECONDS }
import scala.concurrent.{ ExecutionContext, Promise, Future, blocking }
import scala.util.{ Failure, Success, Try }
import scala.util.control.{ NoStackTrace, NonFatal }
import akka.util.Helpers.Requiring

object NettyTransportSettings {
  sealed trait Mode
  case object Tcp extends Mode { override def toString = "tcp" }
  case object Udp extends Mode { override def toString = "udp" }
}

object NettyFutureBridge {
  def apply(nettyFuture: ChannelFuture): Future[Channel] = {
    val p = Promise[Channel]()
    nettyFuture.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture): Unit = p complete Try(
        if (future.isSuccess) future.getChannel
        else if (future.isCancelled) throw new CancellationException
        else throw future.getCause)
    })
    p.future
  }

  def apply(nettyFuture: ChannelGroupFuture): Future[ChannelGroup] = {
    import scala.collection.JavaConverters._
    val p = Promise[ChannelGroup]
    nettyFuture.addListener(new ChannelGroupFutureListener {
      def operationComplete(future: ChannelGroupFuture): Unit = p complete Try(
        if (future.isCompleteSuccess) future.getGroup
        else throw future.iterator.asScala.collectFirst {
          case f if f.isCancelled ⇒ new CancellationException
          case f if !f.isSuccess  ⇒ f.getCause
        } getOrElse new IllegalStateException("Error reported in ChannelGroupFuture, but no error found in individual futures."))
    })
    p.future
  }
}

@SerialVersionUID(1L)
class NettyTransportException(msg: String, cause: Throwable) extends RuntimeException(msg, cause) with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

class NettyTransportSettings(config: Config) {

  import config._

  val TransportMode: Mode = getString("transport-protocol") match {
    case "tcp"   ⇒ Tcp
    case "udp"   ⇒ Udp
    case unknown ⇒ throw new ConfigurationException(s"Unknown transport: [$unknown]")
  }

  val EnableSsl: Boolean = getBoolean("enable-ssl") requiring (!_ || TransportMode == Tcp, s"$TransportMode does not support SSL")

  val UseDispatcherForIo: Option[String] = getString("use-dispatcher-for-io") match {
    case "" | null  ⇒ None
    case dispatcher ⇒ Some(dispatcher)
  }

  private[this] def optionSize(s: String): Option[Int] = getBytes(s).toInt match {
    case 0          ⇒ None
    case x if x < 0 ⇒ throw new ConfigurationException(s"Setting '$s' must be 0 or positive (and fit in an Int)")
    case other      ⇒ Some(other)
  }

  val ConnectionTimeout: FiniteDuration = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val WriteBufferHighWaterMark: Option[Int] = optionSize("write-buffer-high-water-mark")

  val WriteBufferLowWaterMark: Option[Int] = optionSize("write-buffer-low-water-mark")

  val SendBufferSize: Option[Int] = optionSize("send-buffer-size")

  val ReceiveBufferSize: Option[Int] = optionSize("receive-buffer-size")

  val MaxFrameSize: Int = getBytes("maximum-frame-size").toInt requiring (
    _ >= 32000,
    s"Setting 'maximum-frame-size' must be at least 32000 bytes")

  val Backlog: Int = getInt("backlog")

  val TcpNodelay: Boolean = getBoolean("tcp-nodelay")

  val TcpKeepalive: Boolean = getBoolean("tcp-keepalive")

  val TcpReuseAddr: Boolean = getBoolean("tcp-reuse-addr")

  val Hostname: String = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  val SslSettings: Option[SSLSettings] = if (EnableSsl) Some(new SSLSettings(config.getConfig("security"))) else None

  val ServerSocketWorkerPoolSize: Int = computeWPS(config.getConfig("server-socket-worker-pool"))

  val ClientSocketWorkerPoolSize: Int = computeWPS(config.getConfig("client-socket-worker-pool"))

  private def computeWPS(config: Config): Int =
    ThreadPoolConfig.scaledPoolSize(
      config.getInt("pool-size-min"),
      config.getDouble("pool-size-factor"),
      config.getInt("pool-size-max"))

}

/**
 * INTERNAL API
 */
private[netty] trait CommonHandlers extends NettyHelpers {
  protected val transport: NettyTransport

  final override def onOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = transport.channelGroup.add(e.getChannel)

  protected def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle

  protected def registerListener(channel: Channel,
                                 listener: HandleEventListener,
                                 msg: ChannelBuffer,
                                 remoteSocketAddress: InetSocketAddress): Unit

  final protected def init(channel: Channel, remoteSocketAddress: SocketAddress, remoteAddress: Address, msg: ChannelBuffer)(
    op: (AssociationHandle ⇒ Any)): Unit = {
    import transport._
    NettyTransport.addressFromSocketAddress(channel.getLocalAddress, schemeIdentifier, system.name, Some(settings.Hostname)) match {
      case Some(localAddress) ⇒
        val handle = createHandle(channel, localAddress, remoteAddress)
        handle.readHandlerPromise.future.onSuccess {
          case listener: HandleEventListener ⇒
            registerListener(channel, listener, msg, remoteSocketAddress.asInstanceOf[InetSocketAddress])
            channel.setReadable(true)
        }
        op(handle)

      case _ ⇒ NettyTransport.gracefulClose(channel)
    }
  }
}

/**
 * INTERNAL API
 */
private[netty] abstract class ServerHandler(protected final val transport: NettyTransport,
                                            private final val associationListenerFuture: Future[AssociationEventListener])
  extends NettyServerHelpers with CommonHandlers {

  import transport.executionContext

  final protected def initInbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    associationListenerFuture.onSuccess {
      case listener: AssociationEventListener ⇒
        val remoteAddress = NettyTransport.addressFromSocketAddress(remoteSocketAddress, transport.schemeIdentifier,
          transport.system.name, hostName = None).getOrElse(
            throw new NettyTransportException(s"Unknown remote address type [${remoteSocketAddress.getClass.getName}]"))
        init(channel, remoteSocketAddress, remoteAddress, msg) { listener notify InboundAssociation(_) }
    }
  }

}

/**
 * INTERNAL API
 */
private[netty] abstract class ClientHandler(protected final val transport: NettyTransport, remoteAddress: Address)
  extends NettyClientHelpers with CommonHandlers {
  final protected val statusPromise = Promise[AssociationHandle]()
  def statusFuture = statusPromise.future

  final protected def initOutbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    init(channel, remoteSocketAddress, remoteAddress, msg)(statusPromise.success)
  }

}

/**
 * INTERNAL API
 */
private[transport] object NettyTransport {
  // 4 bytes will be used to represent the frame length. Used by netty LengthFieldPrepender downstream handler.
  val FrameLengthFieldLength = 4
  def gracefulClose(channel: Channel)(implicit ec: ExecutionContext): Unit = {
    def always(c: ChannelFuture) = NettyFutureBridge(c) recover { case _ ⇒ c.getChannel }
    for {
      _ ← always { channel.write(ChannelBuffers.buffer(0)) } // Force flush by waiting on a final dummy write
      _ ← always { channel.disconnect() }
    } channel.close()
  }

  val uniqueIdCounter = new AtomicInteger(0)

  def addressFromSocketAddress(addr: SocketAddress, schemeIdentifier: String, systemName: String,
                               hostName: Option[String]): Option[Address] = addr match {
    case sa: InetSocketAddress ⇒ Some(Address(schemeIdentifier, systemName,
      hostName.getOrElse(sa.getAddress.getHostAddress), sa.getPort)) // perhaps use getHostString in jdk 1.7
    case _ ⇒ None
  }
}

// FIXME: Split into separate UDP and TCP classes
class NettyTransport(val settings: NettyTransportSettings, val system: ExtendedActorSystem) extends Transport {

  def this(system: ExtendedActorSystem, conf: Config) = this(new NettyTransportSettings(conf), system)

  import NettyTransport._
  import settings._

  implicit val executionContext: ExecutionContext = system.dispatcher

  override val schemeIdentifier: String = (if (EnableSsl) "ssl." else "") + TransportMode
  override def maximumPayloadBytes: Int = settings.MaxFrameSize

  private final val isDatagram = TransportMode == Udp

  @volatile private var localAddress: Address = _
  @volatile private var serverChannel: Channel = _

  private val log = Logging(system, this.getClass)

  /**
   * INTERNAL API
   */
  private[netty] final val udpConnectionTable = new ConcurrentHashMap[SocketAddress, HandleEventListener]()

  /*
   * Be aware, that the close() method of DefaultChannelGroup is racy, because it uses an iterator over a ConcurrentHashMap.
   * In the old remoting this was handled by using a custom subclass, guarding the close() method with a write-lock.
   * The usage of this class is safe in the new remoting, as close() is called after unbind() is finished, and no
   * outbound connections are initiated in the shutdown phase.
   */
  val channelGroup = new DefaultChannelGroup("akka-netty-transport-driver-channelgroup-" +
    uniqueIdCounter.getAndIncrement)

  private val clientChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒
      val boss, worker = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioClientSocketChannelFactory(boss, worker, ClientSocketWorkerPoolSize)
    case Udp ⇒
      val pool = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioDatagramChannelFactory(pool, ClientSocketWorkerPoolSize)
  }

  private val serverChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒
      val boss, worker = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioServerSocketChannelFactory(boss, worker, ServerSocketWorkerPoolSize)
    case Udp ⇒
      val pool = UseDispatcherForIo.map(system.dispatchers.lookup) getOrElse Executors.newCachedThreadPool()
      new NioDatagramChannelFactory(pool, ServerSocketWorkerPoolSize)
  }

  private def newPipeline: DefaultChannelPipeline = {
    val pipeline = new DefaultChannelPipeline

    if (!isDatagram) {
      pipeline.addLast("FrameDecoder", new LengthFieldBasedFrameDecoder(
        maximumPayloadBytes,
        0,
        FrameLengthFieldLength,
        0,
        FrameLengthFieldLength, // Strip the header
        true))
      pipeline.addLast("FrameEncoder", new LengthFieldPrepender(FrameLengthFieldLength))
    }

    pipeline
  }

  private val associationListenerPromise: Promise[AssociationEventListener] = Promise()

  private def sslHandler(isClient: Boolean): SslHandler = {
    val handler = NettySSLSupport(settings.SslSettings.get, log, isClient)
    handler.setCloseOnSSLException(true)
    handler
  }

  private val serverPipelineFactory: ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", sslHandler(isClient = false))
      val handler = if (isDatagram) new UdpServerHandler(NettyTransport.this, associationListenerPromise.future)
      else new TcpServerHandler(NettyTransport.this, associationListenerPromise.future)
      pipeline.addLast("ServerHandler", handler)
      pipeline
    }
  }

  private def clientPipelineFactory(remoteAddress: Address): ChannelPipelineFactory =
    new ChannelPipelineFactory {
      override def getPipeline: ChannelPipeline = {
        val pipeline = newPipeline
        if (EnableSsl) pipeline.addFirst("SslHandler", sslHandler(isClient = true))
        val handler = if (isDatagram) new UdpClientHandler(NettyTransport.this, remoteAddress)
        else new TcpClientHandler(NettyTransport.this, remoteAddress)
        pipeline.addLast("clienthandler", handler)
        pipeline
      }
    }

  private def setupBootstrap[B <: Bootstrap](bootstrap: B, pipelineFactory: ChannelPipelineFactory): B = {
    bootstrap.setPipelineFactory(pipelineFactory)
    bootstrap.setOption("backlog", settings.Backlog)
    bootstrap.setOption("tcpNoDelay", settings.TcpNodelay)
    bootstrap.setOption("child.keepAlive", settings.TcpKeepalive)
    bootstrap.setOption("reuseAddress", settings.TcpReuseAddr)
    if (isDatagram) bootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(ReceiveBufferSize.get))
    settings.ReceiveBufferSize.foreach(sz ⇒ bootstrap.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz ⇒ bootstrap.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferLowWaterMark", sz))
    bootstrap
  }

  private val inboundBootstrap: Bootstrap = settings.TransportMode match {
    case Tcp ⇒ setupBootstrap(new ServerBootstrap(serverChannelFactory), serverPipelineFactory)
    case Udp ⇒ setupBootstrap(new ConnectionlessBootstrap(serverChannelFactory), serverPipelineFactory)
  }

  private def outboundBootstrap(remoteAddress: Address): ClientBootstrap = {
    val bootstrap = setupBootstrap(new ClientBootstrap(clientChannelFactory), clientPipelineFactory(remoteAddress))
    bootstrap.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
    bootstrap.setOption("tcpNoDelay", settings.TcpNodelay)
    bootstrap.setOption("keepAlive", settings.TcpKeepalive)
    settings.ReceiveBufferSize.foreach(sz ⇒ bootstrap.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz ⇒ bootstrap.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz ⇒ bootstrap.setOption("writeBufferLowWaterMark", sz))
    bootstrap
  }

  override def isResponsibleFor(address: Address): Boolean = true //TODO: Add configurable subnet filtering

  // TODO: This should be factored out to an async (or thread-isolated) name lookup service #2960
  def addressToSocketAddress(addr: Address): Future[InetSocketAddress] =
    Future { new InetSocketAddress(InetAddress.getByName(addr.host.get), addr.port.get) }

  override def listen: Future[(Address, Promise[AssociationEventListener])] = {
    for {
      address ← addressToSocketAddress(Address("", "", settings.Hostname, settings.PortSelector))
    } yield {
      val newServerChannel = inboundBootstrap match {
        case b: ServerBootstrap         ⇒ b.bind(address)
        case b: ConnectionlessBootstrap ⇒ b.bind(address)
      }

      // Block reads until a handler actor is registered
      newServerChannel.setReadable(false)
      channelGroup.add(newServerChannel)

      serverChannel = newServerChannel

      addressFromSocketAddress(newServerChannel.getLocalAddress, schemeIdentifier, system.name, Some(settings.Hostname)) match {
        case Some(address) ⇒
          localAddress = address
          associationListenerPromise.future.onSuccess { case listener ⇒ newServerChannel.setReadable(true) }
          (address, associationListenerPromise)
        case None ⇒ throw new NettyTransportException(s"Unknown local address type [${newServerChannel.getLocalAddress.getClass.getName}]")
      }
    }
  }

  override def associate(remoteAddress: Address): Future[AssociationHandle] = {
    if (!serverChannel.isBound) Future.failed(new NettyTransportException("Transport is not bound"))
    else {
      val bootstrap: ClientBootstrap = outboundBootstrap(remoteAddress)

      (for {
        socketAddress ← addressToSocketAddress(remoteAddress)
        readyChannel ← NettyFutureBridge(bootstrap.connect(socketAddress)) map {
          channel ⇒
            if (EnableSsl)
              blocking {
                channel.getPipeline.get(classOf[SslHandler]).handshake().awaitUninterruptibly()
              }
            if (!isDatagram) channel.setReadable(false)
            channel
        }
        handle ← if (isDatagram)
          Future {
            readyChannel.getRemoteAddress match {
              case addr: InetSocketAddress ⇒
                val handle = new UdpAssociationHandle(localAddress, remoteAddress, readyChannel, NettyTransport.this)
                handle.readHandlerPromise.future.onSuccess {
                  case listener ⇒ udpConnectionTable.put(addr, listener)
                }
                handle
              case unknown ⇒ throw new NettyTransportException(s"Unknown remote address type [${unknown.getClass.getName}]")
            }
          }
        else
          readyChannel.getPipeline.get(classOf[ClientHandler]).statusFuture
      } yield handle) recover {
        case c: CancellationException ⇒ throw new NettyTransportException("Connection was cancelled") with NoStackTrace
        case u @ (_: UnknownHostException | _: SecurityException) ⇒ throw new InvalidAssociationException(u.getMessage, u.getCause)
        case NonFatal(t) ⇒ throw new NettyTransportException(t.getMessage, t.getCause) with NoStackTrace
      }
    }
  }

  override def shutdown(): Future[Boolean] = {
    def always(c: ChannelGroupFuture) = NettyFutureBridge(c).map(_ ⇒ true) recover { case _ ⇒ false }
    for {
      // Force flush by trying to write an empty buffer and wait for success
      lastWriteStatus ← always(channelGroup.write(ChannelBuffers.buffer(0)))
      unbindStatus ← always(channelGroup.unbind())
      disconnectStatus ← always(channelGroup.disconnect())
      closeStatus ← always(channelGroup.close())
    } yield {
      // Release the selectors, but don't try to kill the dispatcher
      if (UseDispatcherForIo.isDefined) {
        clientChannelFactory.shutdown()
        serverChannelFactory.shutdown()
      } else {
        clientChannelFactory.releaseExternalResources()
        serverChannelFactory.releaseExternalResources()
      }
      lastWriteStatus && unbindStatus && disconnectStatus && closeStatus
    }

  }

}

