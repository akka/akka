/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.transport.netty

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.blocking
import scala.util.Try
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.dispatch.ThreadPoolConfig
import akka.event.Logging
import akka.remote.RARP
import akka.remote.transport.AssociationHandle.HandleEventListener
import akka.remote.transport.Transport._
import akka.remote.transport.AssociationHandle
import akka.remote.transport.Transport
import akka.util.Helpers
import akka.util.Helpers.Requiring
import akka.util.OptionVal
import akka.ConfigurationException
import akka.OnlyCauseStackTrace
import com.github.ghik.silencer.silent
import com.typesafe.config.Config
import org.jboss.netty.bootstrap.Bootstrap
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.bootstrap.ConnectionlessBootstrap
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.ChannelGroup
import org.jboss.netty.channel.group.ChannelGroupFuture
import org.jboss.netty.channel.group.ChannelGroupFutureListener
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioWorkerPool
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder
import org.jboss.netty.handler.codec.frame.LengthFieldPrepender
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.util.HashedWheelTimer

object NettyFutureBridge {
  def apply(nettyFuture: ChannelFuture): Future[Channel] = {
    val p = Promise[Channel]()
    nettyFuture.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture): Unit =
        p.complete(
          Try(
            if (future.isSuccess) future.getChannel
            else if (future.isCancelled) throw new CancellationException
            else throw future.getCause))
    })
    p.future
  }

  def apply(nettyFuture: ChannelGroupFuture): Future[ChannelGroup] = {
    import akka.util.ccompat.JavaConverters._
    val p = Promise[ChannelGroup]
    nettyFuture.addListener(new ChannelGroupFutureListener {
      def operationComplete(future: ChannelGroupFuture): Unit =
        p.complete(
          Try(
            if (future.isCompleteSuccess) future.getGroup
            else
              throw future.iterator.asScala
                .collectFirst {
                  case f if f.isCancelled => new CancellationException
                  case f if !f.isSuccess  => f.getCause
                }
                .getOrElse(new IllegalStateException(
                  "Error reported in ChannelGroupFuture, but no error found in individual futures."))))
    })
    p.future
  }
}

@SerialVersionUID(1L)
class NettyTransportException(msg: String, cause: Throwable)
    extends RuntimeException(msg, cause)
    with OnlyCauseStackTrace {
  def this(msg: String) = this(msg, null)
}

@SerialVersionUID(1L)
class NettyTransportExceptionNoStack(msg: String, cause: Throwable)
    extends NettyTransportException(msg, cause)
    with NoStackTrace {
  def this(msg: String) = this(msg, null)
}

class NettyTransportSettings(config: Config) {

  import akka.util.Helpers.ConfigOps
  import config._

  val EnableSsl: Boolean = getBoolean("enable-ssl")

  val SSLEngineProviderClassName: String = if (EnableSsl) getString("ssl-engine-provider") else ""

  val UseDispatcherForIo: Option[String] = getString("use-dispatcher-for-io") match {
    case "" | null  => None
    case dispatcher => Some(dispatcher)
  }

  private[this] def optionSize(s: String): Option[Int] = getBytes(s).toInt match {
    case 0          => None
    case x if x < 0 => throw new ConfigurationException(s"Setting '$s' must be 0 or positive (and fit in an Int)")
    case other      => Some(other)
  }

  val ConnectionTimeout: FiniteDuration = config.getMillisDuration("connection-timeout")

  val WriteBufferHighWaterMark: Option[Int] = optionSize("write-buffer-high-water-mark")

  val WriteBufferLowWaterMark: Option[Int] = optionSize("write-buffer-low-water-mark")

  val SendBufferSize: Option[Int] = optionSize("send-buffer-size")

  val ReceiveBufferSize: Option[Int] = optionSize("receive-buffer-size")

  val MaxFrameSize: Int = getBytes("maximum-frame-size").toInt
    .requiring(_ >= 32000, s"Setting 'maximum-frame-size' must be at least 32000 bytes")

  val Backlog: Int = getInt("backlog")

  val TcpNodelay: Boolean = getBoolean("tcp-nodelay")

  val TcpKeepalive: Boolean = getBoolean("tcp-keepalive")

  val TcpReuseAddr: Boolean = getString("tcp-reuse-addr") match {
    case "off-for-windows" => !Helpers.isWindows
    case _                 => getBoolean("tcp-reuse-addr")
  }

  val Hostname: String = getString("hostname") match {
    case ""    => InetAddress.getLocalHost.getHostAddress
    case value => value
  }

  val BindHostname: String = getString("bind-hostname") match {
    case ""    => Hostname
    case value => value
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  @deprecated("WARNING: This should only be used by professionals.", "2.4")
  @silent
  val BindPortSelector: Int = getString("bind-port") match {
    case ""    => PortSelector
    case value => value.toInt
  }

  val SslSettings: Option[SSLSettings] = if (EnableSsl) Some(new SSLSettings(config.getConfig("security"))) else None

  val ServerSocketWorkerPoolSize: Int = computeWPS(config.getConfig("server-socket-worker-pool"))

  val ClientSocketWorkerPoolSize: Int = computeWPS(config.getConfig("client-socket-worker-pool"))

  private def computeWPS(config: Config): Int =
    ThreadPoolConfig.scaledPoolSize(
      config.getInt("pool-size-min"),
      config.getDouble("pool-size-factor"),
      config.getInt("pool-size-max"))

  // Check Netty version >= 3.10.6
  {
    val nettyVersion = org.jboss.netty.util.Version.ID
    def throwInvalidNettyVersion(): Nothing = {
      throw new IllegalArgumentException(
        "akka-remote with the Netty transport requires Netty version 3.10.6 or " +
        s"later. Version [$nettyVersion] is on the class path. Issue https://github.com/netty/netty/pull/4739 " +
        "may cause messages to not be delivered.")
    }

    try {
      val segments: Array[String] = nettyVersion.split("[.-]")
      if (segments.length < 3 || segments(0).toInt != 3 || segments(1).toInt != 10 || segments(2).toInt < 6)
        throwInvalidNettyVersion()
    } catch {
      case _: NumberFormatException =>
        throwInvalidNettyVersion()
    }
  }

}

/**
 * INTERNAL API
 */
private[netty] trait CommonHandlers extends NettyHelpers {
  protected val transport: NettyTransport

  final override def onOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit =
    transport.channelGroup.add(e.getChannel)

  protected def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle

  protected def registerListener(
      channel: Channel,
      listener: HandleEventListener,
      msg: ChannelBuffer,
      remoteSocketAddress: InetSocketAddress): Unit

  final protected def init(
      channel: Channel,
      remoteSocketAddress: SocketAddress,
      remoteAddress: Address,
      msg: ChannelBuffer)(op: AssociationHandle => Any): Unit = {
    import transport._
    NettyTransport.addressFromSocketAddress(
      channel.getLocalAddress,
      schemeIdentifier,
      system.name,
      Some(settings.Hostname),
      None) match {
      case Some(localAddress) =>
        val handle = createHandle(channel, localAddress, remoteAddress)
        handle.readHandlerPromise.future.foreach { listener =>
          registerListener(channel, listener, msg, remoteSocketAddress.asInstanceOf[InetSocketAddress])
          channel.setReadable(true)
        }
        op(handle)

      case _ => NettyTransport.gracefulClose(channel)
    }
  }
}

/**
 * INTERNAL API
 */
private[netty] abstract class ServerHandler(
    protected final val transport: NettyTransport,
    private final val associationListenerFuture: Future[AssociationEventListener])
    extends NettyServerHelpers
    with CommonHandlers {

  import transport.executionContext

  final protected def initInbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    associationListenerFuture.foreach { listener =>
      val remoteAddress = NettyTransport
        .addressFromSocketAddress(
          remoteSocketAddress,
          transport.schemeIdentifier,
          transport.system.name,
          hostName = None,
          port = None)
        .getOrElse(throw new NettyTransportException(
          s"Unknown inbound remote address type [${remoteSocketAddress.getClass.getName}]"))
      init(channel, remoteSocketAddress, remoteAddress, msg) { a =>
        listener.notify(InboundAssociation(a))
      }
    }
  }

}

/**
 * INTERNAL API
 */
private[netty] abstract class ClientHandler(protected final val transport: NettyTransport, remoteAddress: Address)
    extends NettyClientHelpers
    with CommonHandlers {
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
    def always(c: ChannelFuture) = NettyFutureBridge(c).recover { case _ => c.getChannel }
    for {
      _ <- always { channel.write(ChannelBuffers.buffer(0)) } // Force flush by waiting on a final dummy write
      _ <- always { channel.disconnect() }
    } channel.close()
  }

  val uniqueIdCounter = new AtomicInteger(0)

  def addressFromSocketAddress(
      addr: SocketAddress,
      schemeIdentifier: String,
      systemName: String,
      hostName: Option[String],
      port: Option[Int]): Option[Address] = addr match {
    case sa: InetSocketAddress =>
      Some(Address(schemeIdentifier, systemName, hostName.getOrElse(sa.getHostString), port.getOrElse(sa.getPort)))
    case _ => None
  }

  // Need to do like this for binary compatibility reasons
  def addressFromSocketAddress(
      addr: SocketAddress,
      schemeIdentifier: String,
      systemName: String,
      hostName: Option[String]): Option[Address] =
    addressFromSocketAddress(addr, schemeIdentifier, systemName, hostName, port = None)
}

class NettyTransport(val settings: NettyTransportSettings, val system: ExtendedActorSystem) extends Transport {

  def this(system: ExtendedActorSystem, conf: Config) = this(new NettyTransportSettings(conf), system)

  import NettyTransport._
  import settings._

  implicit val executionContext: ExecutionContext =
    settings.UseDispatcherForIo
      .orElse(RARP(system).provider.remoteSettings.Dispatcher match {
        case ""             => None
        case dispatcherName => Some(dispatcherName)
      })
      .map(system.dispatchers.lookup)
      .getOrElse(system.dispatcher)

  override val schemeIdentifier: String = (if (EnableSsl) "ssl." else "") + "tcp"
  override def maximumPayloadBytes: Int = settings.MaxFrameSize

  @volatile private var boundTo: Address = _
  @volatile private var serverChannel: Channel = _

  private val log = Logging.withMarker(system, this.getClass)

  /**
   * INTERNAL API
   */
  private[netty] final val udpConnectionTable = new ConcurrentHashMap[SocketAddress, HandleEventListener]()

  private def createExecutorService() =
    UseDispatcherForIo.map(system.dispatchers.lookup).getOrElse(Executors.newCachedThreadPool(system.threadFactory))

  /*
   * Be aware, that the close() method of DefaultChannelGroup is racy, because it uses an iterator over a ConcurrentHashMap.
   * In the old remoting this was handled by using a custom subclass, guarding the close() method with a write-lock.
   * The usage of this class is safe in the new remoting, as close() is called after unbind() is finished, and no
   * outbound connections are initiated in the shutdown phase.
   */
  val channelGroup = new DefaultChannelGroup(
    "akka-netty-transport-driver-channelgroup-" +
    uniqueIdCounter.getAndIncrement)

  private val clientChannelFactory: ChannelFactory = {
    val boss, worker = createExecutorService()
    new NioClientSocketChannelFactory(
      boss,
      1,
      new NioWorkerPool(worker, ClientSocketWorkerPoolSize),
      new HashedWheelTimer(system.threadFactory))
  }

  private val serverChannelFactory: ChannelFactory = {
    val boss, worker = createExecutorService()
    // This does not create a HashedWheelTimer internally
    new NioServerSocketChannelFactory(boss, worker, ServerSocketWorkerPoolSize)
  }

  private def newPipeline: DefaultChannelPipeline = {
    val pipeline = new DefaultChannelPipeline
    pipeline.addLast(
      "FrameDecoder",
      new LengthFieldBasedFrameDecoder(
        maximumPayloadBytes,
        0,
        FrameLengthFieldLength,
        0,
        FrameLengthFieldLength, // Strip the header
        true))
    pipeline.addLast("FrameEncoder", new LengthFieldPrepender(FrameLengthFieldLength))

    pipeline
  }

  private val associationListenerPromise: Promise[AssociationEventListener] = Promise()

  private val sslEngineProvider: OptionVal[SSLEngineProvider] =
    if (settings.EnableSsl) {
      OptionVal.Some(system.dynamicAccess
        .createInstanceFor[SSLEngineProvider](settings.SSLEngineProviderClassName, List((classOf[ActorSystem], system)))
        .recover {
          case e =>
            throw new ConfigurationException(
              s"Could not create SSLEngineProvider [${settings.SSLEngineProviderClassName}]",
              e)
        }
        .get)
    } else OptionVal.None

  private def sslHandler(isClient: Boolean): SslHandler = {
    sslEngineProvider match {
      case OptionVal.Some(sslProvider) =>
        val handler = NettySSLSupport(sslProvider, isClient)
        handler.setCloseOnSSLException(true)
        handler
      case OptionVal.None =>
        throw new IllegalStateException("Expected enable-ssl=on")
    }

  }

  private val serverPipelineFactory: ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", sslHandler(isClient = false))
      val handler = new TcpServerHandler(NettyTransport.this, associationListenerPromise.future, log)
      pipeline.addLast("ServerHandler", handler)
      pipeline
    }
  }

  private def clientPipelineFactory(remoteAddress: Address): ChannelPipelineFactory =
    new ChannelPipelineFactory {
      override def getPipeline: ChannelPipeline = {
        val pipeline = newPipeline
        if (EnableSsl) pipeline.addFirst("SslHandler", sslHandler(isClient = true))
        val handler = new TcpClientHandler(NettyTransport.this, remoteAddress, log)
        pipeline.addLast("clienthandler", handler)
        pipeline
      }
    }

  private def setupBootstrap[B <: Bootstrap](bootstrap: B, pipelineFactory: ChannelPipelineFactory): B = {
    bootstrap.setPipelineFactory(pipelineFactory)
    bootstrap.setOption("backlog", settings.Backlog)
    bootstrap.setOption("child.tcpNoDelay", settings.TcpNodelay)
    bootstrap.setOption("child.keepAlive", settings.TcpKeepalive)
    bootstrap.setOption("reuseAddress", settings.TcpReuseAddr)
    settings.ReceiveBufferSize.foreach(sz => bootstrap.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz => bootstrap.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz => bootstrap.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz => bootstrap.setOption("writeBufferLowWaterMark", sz))
    bootstrap
  }

  private val inboundBootstrap: Bootstrap = {
    setupBootstrap(new ServerBootstrap(serverChannelFactory), serverPipelineFactory)
  }

  private def outboundBootstrap(remoteAddress: Address): ClientBootstrap = {
    val bootstrap = setupBootstrap(new ClientBootstrap(clientChannelFactory), clientPipelineFactory(remoteAddress))
    bootstrap.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
    bootstrap.setOption("tcpNoDelay", settings.TcpNodelay)
    bootstrap.setOption("keepAlive", settings.TcpKeepalive)
    settings.ReceiveBufferSize.foreach(sz => bootstrap.setOption("receiveBufferSize", sz))
    settings.SendBufferSize.foreach(sz => bootstrap.setOption("sendBufferSize", sz))
    settings.WriteBufferHighWaterMark.foreach(sz => bootstrap.setOption("writeBufferHighWaterMark", sz))
    settings.WriteBufferLowWaterMark.foreach(sz => bootstrap.setOption("writeBufferLowWaterMark", sz))
    bootstrap
  }

  override def isResponsibleFor(address: Address): Boolean = true //TODO: Add configurable subnet filtering

  // TODO: This should be factored out to an async (or thread-isolated) name lookup service #2960
  def addressToSocketAddress(addr: Address): Future[InetSocketAddress] = addr match {
    case Address(_, _, Some(host), Some(port)) =>
      Future { blocking { new InetSocketAddress(InetAddress.getByName(host), port) } }
    case _ => Future.failed(new IllegalArgumentException(s"Address [$addr] does not contain host or port information."))
  }

  override def listen: Future[(Address, Promise[AssociationEventListener])] = {
    @silent
    val bindPort = settings.BindPortSelector

    for {
      address <- addressToSocketAddress(Address("", "", settings.BindHostname, bindPort))
    } yield {
      try {
        val newServerChannel = inboundBootstrap match {
          case b: ServerBootstrap         => b.bind(address)
          case b: ConnectionlessBootstrap => b.bind(address)
        }

        // Block reads until a handler actor is registered
        newServerChannel.setReadable(false)
        channelGroup.add(newServerChannel)

        serverChannel = newServerChannel

        @silent
        val port = if (settings.PortSelector == 0) None else Some(settings.PortSelector)

        addressFromSocketAddress(
          newServerChannel.getLocalAddress,
          schemeIdentifier,
          system.name,
          Some(settings.Hostname),
          port) match {
          case Some(address) =>
            addressFromSocketAddress(newServerChannel.getLocalAddress, schemeIdentifier, system.name, None, None) match {
              case Some(address) => boundTo = address
              case None =>
                throw new NettyTransportException(
                  s"Unknown local address type [${newServerChannel.getLocalAddress.getClass.getName}]")
            }
            associationListenerPromise.future.foreach { _ =>
              newServerChannel.setReadable(true)
            }
            (address, associationListenerPromise)
          case None =>
            throw new NettyTransportException(
              s"Unknown local address type [${newServerChannel.getLocalAddress.getClass.getName}]")
        }
      } catch {
        case NonFatal(e) => {
          log.error("failed to bind to {}, shutting down Netty transport", address)
          try {
            shutdown()
          } catch { case NonFatal(_) => } // ignore possible exception during shutdown
          throw e
        }
      }
    }
  }

  // Need to do like this for binary compatibility reasons
  private[akka] def boundAddress = boundTo

  override def associate(remoteAddress: Address): Future[AssociationHandle] = {
    if (!serverChannel.isBound) Future.failed(new NettyTransportException("Transport is not bound"))
    else {
      val bootstrap: ClientBootstrap = outboundBootstrap(remoteAddress)

      (for {
        socketAddress <- addressToSocketAddress(remoteAddress)
        readyChannel <- NettyFutureBridge(bootstrap.connect(socketAddress)).map { channel =>
          if (EnableSsl)
            blocking {
              channel.getPipeline.get(classOf[SslHandler]).handshake().awaitUninterruptibly()
            }
          channel.setReadable(false)
          channel
        }
        handle <- readyChannel.getPipeline.get(classOf[ClientHandler]).statusFuture
      } yield handle).recover {
        case _: CancellationException => throw new NettyTransportExceptionNoStack("Connection was cancelled")
        case NonFatal(t) =>
          val msg =
            if (t.getCause == null)
              t.getMessage
            else if (t.getCause.getCause == null)
              s"${t.getMessage}, caused by: ${t.getCause}"
            else
              s"${t.getMessage}, caused by: ${t.getCause}, caused by: ${t.getCause.getCause}"
          throw new NettyTransportExceptionNoStack(s"${t.getClass.getName}: $msg", t.getCause)
      }
    }
  }

  override def shutdown(): Future[Boolean] = {
    def always(c: ChannelGroupFuture) = NettyFutureBridge(c).map(_ => true).recover { case _ => false }
    for {
      // Force flush by trying to write an empty buffer and wait for success
      unbindStatus <- always(channelGroup.unbind())
      lastWriteStatus <- always(channelGroup.write(ChannelBuffers.buffer(0)))
      disconnectStatus <- always(channelGroup.disconnect())
      closeStatus <- always(channelGroup.close())
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
