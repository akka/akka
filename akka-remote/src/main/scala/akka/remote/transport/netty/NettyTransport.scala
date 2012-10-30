package akka.remote.transport.netty

import akka.ConfigurationException
import akka.actor.{ Address, ExtendedActorSystem, ActorRef }
import akka.event.Logging
import akka.remote.netty.{ SslSettings, NettySSLSupport, DefaultDisposableChannelGroup }
import akka.remote.transport.Transport._
import akka.remote.transport.netty.NettyTransportSettings.{ Udp, Tcp, Mode }
import akka.remote.transport.{ AssociationHandle, Transport }
import com.typesafe.config.Config
import java.net.{ UnknownHostException, SocketAddress, InetAddress, InetSocketAddress }
import java.util.concurrent.{ ConcurrentHashMap, Executor, Executors }
import org.jboss.netty.bootstrap.{ ConnectionlessBootstrap, Bootstrap, ClientBootstrap, ServerBootstrap }
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel._
import org.jboss.netty.channel.group.{ ChannelGroupFuture, ChannelGroupFutureListener }
import org.jboss.netty.channel.socket.nio.{ NioDatagramChannelFactory, NioServerSocketChannelFactory, NioClientSocketChannelFactory }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import scala.concurrent.duration.{ Duration, FiniteDuration, MILLISECONDS }
import scala.concurrent.{ ExecutionContext, Promise, Future }
import scala.util.Random
import scala.util.control.NonFatal

object NettyTransportSettings {
  sealed trait Mode
  case object Tcp extends Mode { override def toString = "tcp" }
  case object Udp extends Mode { override def toString = "udp" }
}

class NettyTransportException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

class NettyTransportSettings(config: Config) {

  import config._

  val TransportMode: Mode = getString("transport-protocol") match {
    case "tcp" ⇒ Tcp
    case "udp" ⇒ Udp
    case s @ _ ⇒ throw new ConfigurationException("Unknown transport: " + s)
  }

  val EnableSsl: Boolean = if (getBoolean("enable-ssl") && TransportMode == Udp)
    throw new ConfigurationException("UDP transport does not support SSL")
  else getBoolean("enable-ssl")

  val UseDispatcherForIo: Option[String] = getString("use-dispatcher-for-io") match {
    case "" | null  ⇒ None
    case dispatcher ⇒ Some(dispatcher)
  }

  private[this] def optionSize(s: String): Option[Int] = getBytes(s).toInt match {
    case 0 ⇒ None
    case x if x < 0 ⇒
      throw new ConfigurationException(s"Setting '$s' must be 0 or positive (and fit in an Int)")
    case other ⇒ Some(other)
  }

  val ConnectionTimeout: FiniteDuration = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)

  val WriteBufferHighWaterMark: Option[Int] = optionSize("write-buffer-high-water-mark")

  val WriteBufferLowWaterMark: Option[Int] = optionSize("write-buffer-low-water-mark")

  val SendBufferSize: Option[Int] = optionSize("send-buffer-size")

  val ReceiveBufferSize: Option[Int] = optionSize("receive-buffer-size")

  val Backlog: Int = getInt("backlog")

  val Hostname: String = getString("hostname") match {
    case ""    ⇒ InetAddress.getLocalHost.getHostAddress
    case value ⇒ value
  }

  @deprecated("WARNING: This should only be used by professionals.", "2.0")
  val PortSelector: Int = getInt("port")

  val SslSettings: Option[SslSettings] = if (EnableSsl) Some(new SslSettings(config.getConfig("ssl"))) else None

}

trait HasTransport {
  protected val transport: NettyTransport
}

trait CommonHandlers extends NettyHelpers with HasTransport {
  import transport.executionContext

  final override def onOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent): Unit = transport.channels.add(e.getChannel)

  protected def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle

  protected def registerReader(channel: Channel, readerRef: ActorRef, msg: ChannelBuffer, remoteSocketAddress: InetSocketAddress): Unit

  final protected def init(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer)(op: (AssociationHandle ⇒ Any)): Unit = {
    import transport._
    (addressFromSocketAddress(channel.getLocalAddress), addressFromSocketAddress(remoteSocketAddress)) match {
      case (Some(localAddress), Some(remoteAddress)) ⇒
        val handle = createHandle(channel, localAddress, remoteAddress)
        handle.readHandlerPromise.future.onSuccess {
          case readerRef: ActorRef ⇒
            registerReader(channel, readerRef, msg, remoteSocketAddress.asInstanceOf[InetSocketAddress])
            channel.setReadable(true)
        }
        op(handle)

      case _ ⇒ NettyTransport.gracefulClose(channel)
    }
  }
}

abstract class ServerHandler(protected final val transport: NettyTransport,
                             private final val associationHandlerFuture: Future[ActorRef])
  extends NettyServerHelpers with CommonHandlers with HasTransport {
  import transport.executionContext

  final protected def initInbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    associationHandlerFuture.onSuccess {
      case ref: ActorRef ⇒ init(channel, remoteSocketAddress, msg) { ref ! InboundAssociation(_) }
    }
  }

}

abstract class ClientHandler(protected final val transport: NettyTransport,
                             private final val statusPromise: Promise[Status])
  extends NettyClientHelpers with CommonHandlers with HasTransport {

  final protected def initOutbound(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit = {
    channel.setReadable(false)
    init(channel, remoteSocketAddress, msg) { handle ⇒ statusPromise.success(Ready(handle)) }
  }

}

private[transport] object NettyTransport {
  val FrameLengthFieldLength = 4
  def gracefulClose(channel: Channel): Unit = channel.disconnect().addListener(ChannelFutureListener.CLOSE)

}

class NettyTransport(private val settings: NettyTransportSettings, private val system: ExtendedActorSystem) extends Transport {

  def this(system: ExtendedActorSystem, conf: Config) = this(new NettyTransportSettings(conf), system)

  import NettyTransport._
  import settings._
  implicit val executionContext: ExecutionContext = system.dispatcher

  override val schemeIdentifier: String = TransportMode + (if (EnableSsl) ".ssl" else "")
  override val maximumPayloadBytes: Int = 32000

  private final val isDatagram: Boolean = TransportMode == Udp

  @volatile private var localAddress: Address = _
  @volatile private var masterChannel: Channel = _

  private val log = Logging(system, this.getClass)

  final val udpConnectionTable = new ConcurrentHashMap[SocketAddress, ActorRef]()

  val channels = new DefaultDisposableChannelGroup("netty-transport-" + Random.nextString(20))

  private def executor: Executor = UseDispatcherForIo match {
    case Some(dispatcherName) ⇒ system.dispatchers.lookup(dispatcherName)
    case None                 ⇒ Executors.newCachedThreadPool() // FIXME: apply patch from #2659 when available
  }

  private val clientChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒ new NioClientSocketChannelFactory(executor, executor)
    case Udp ⇒ new NioDatagramChannelFactory(executor)
  }

  private val serverChannelFactory: ChannelFactory = TransportMode match {
    case Tcp ⇒ new NioServerSocketChannelFactory(executor, executor)
    case Udp ⇒ new NioDatagramChannelFactory(executor)
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

  private val associationHandlerPromise: Promise[ActorRef] = Promise()
  private val serverPipelineFactory: ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", NettySSLSupport(settings.SslSettings.get, log, false))
      val handler = if (isDatagram) new UdpServerHandler(NettyTransport.this, associationHandlerPromise.future)
      else new TcpServerHandler(NettyTransport.this, associationHandlerPromise.future)
      pipeline.addLast("ServerHandler", handler)
      pipeline
    }
  }

  private def clientPipelineFactory(statusPromise: Promise[Status]): ChannelPipelineFactory = new ChannelPipelineFactory {
    override def getPipeline: ChannelPipeline = {
      val pipeline = newPipeline
      if (EnableSsl) pipeline.addFirst("SslHandler", NettySSLSupport(settings.SslSettings.get, log, true))
      val handler = if (isDatagram) new UdpClientHandler(NettyTransport.this, statusPromise)
      else new TcpClientHandler(NettyTransport.this, statusPromise)
      pipeline.addLast("clienthandler", handler)
      pipeline
    }
  }

  private def setupBootstrap[B <: Bootstrap](bootstrap: B, pipelineFactory: ChannelPipelineFactory): B = {
    bootstrap.setPipelineFactory(pipelineFactory)
    bootstrap.setOption("backlog", settings.Backlog)
    bootstrap.setOption("tcpNoDelay", true)
    bootstrap.setOption("child.keepAlive", true)
    bootstrap.setOption("reuseAddress", true)
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

  private def outboundBootstrap(statusPromise: Promise[Status]): ClientBootstrap = {
    val bootstrap = setupBootstrap(new ClientBootstrap(clientChannelFactory), clientPipelineFactory(statusPromise))
    bootstrap.setOption("connectTimeoutMillis", settings.ConnectionTimeout.toMillis)
    bootstrap
  }

  override def isResponsibleFor(address: Address): Boolean = true //TODO: Add configurable subnet filtering

  def addressFromSocketAddress(addr: SocketAddress,
                               systemName: Option[String] = None,
                               hostName: Option[String] = None): Option[Address] = {
    addr match {
      case sa: InetSocketAddress ⇒
        Some(Address(schemeIdentifier, systemName.getOrElse(""), hostName.getOrElse(sa.getHostName), sa.getPort))

      case _ ⇒ None
    }
  }

  def addressToSocketAddress(addr: Address): InetSocketAddress =
    new InetSocketAddress(InetAddress.getByName(addr.host.get), addr.port.get)

  override def listen: Future[(Address, Promise[ActorRef])] = {
    val listenPromise: Promise[(Address, Promise[ActorRef])] = Promise()

    try {
      masterChannel = inboundBootstrap match {
        case b: ServerBootstrap ⇒ b.bind(new InetSocketAddress(InetAddress.getByName(settings.Hostname), settings.PortSelector))
        case b: ConnectionlessBootstrap ⇒
          b.bind(new InetSocketAddress(InetAddress.getByName(settings.Hostname), settings.PortSelector))
      }

      // Block reads until a handler actor is registered
      masterChannel.setReadable(false)
      channels.add(masterChannel)

      addressFromSocketAddress(masterChannel.getLocalAddress, Some(system.name), Some(settings.Hostname)) match {
        case Some(address) ⇒
          val handlerPromise: Promise[ActorRef] = Promise()
          listenPromise.success((address, handlerPromise))
          localAddress = address
          handlerPromise.future.onSuccess {
            case ref: ActorRef ⇒
              associationHandlerPromise.success(ref)
              masterChannel.setReadable(true)
          }

        case None ⇒
          listenPromise.failure(
            new NettyTransportException(s"Unknown local address type ${masterChannel.getLocalAddress.getClass}", null))
      }

    } catch {
      case NonFatal(e) ⇒ listenPromise.failure(e)
    }

    listenPromise.future
  }

  override def associate(remoteAddress: Address): Future[Status] = {
    val statusPromise: Promise[Status] = Promise()

    if (!masterChannel.isBound) statusPromise.success(Fail(new NettyTransportException("Transport is not bound", null)))

    try {
      if (!isDatagram) {
        val connectFuture = outboundBootstrap(statusPromise).connect(addressToSocketAddress(remoteAddress))

        connectFuture.addListener(new ChannelFutureListener {
          override def operationComplete(future: ChannelFuture) {
            if (!future.isSuccess)
              statusPromise.failure(future.getCause)
            else if (future.isCancelled)
              statusPromise.failure(new NettyTransportException("Connection was cancelled", null))

          }
        })

      } else {
        val connectFuture = outboundBootstrap(statusPromise).connect(addressToSocketAddress(remoteAddress))

        connectFuture.addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            if (!future.isSuccess)
              statusPromise.failure(future.getCause)
            else if (future.isCancelled)
              statusPromise.failure(new NettyTransportException("Connection was cancelled", null))
            else {
              val handle: UdpAssociationHandle = new UdpAssociationHandle(localAddress, remoteAddress, future.getChannel, NettyTransport.this)

              future.getChannel.getRemoteAddress match {
                case addr: InetSocketAddress ⇒
                  statusPromise.success(Ready(handle))
                  handle.readHandlerPromise.future.onSuccess {
                    case ref: ActorRef ⇒ udpConnectionTable.put(addr, ref)
                  }
                case a @ _ ⇒ statusPromise.success(Fail(
                  new NettyTransportException("Unknown remote address type " + a.getClass, null)))
              }
            }
          }
        })
      }

    } catch {

      case e @ (_: UnknownHostException | _: SecurityException | _: IllegalArgumentException) ⇒
        statusPromise.success(Invalid(e))

      case NonFatal(e) ⇒
        statusPromise.success(Fail(e))
    }

    statusPromise.future
  }

  override def shutdown(): Unit = {
    channels.unbind()
    channels.disconnect().addListener(new ChannelGroupFutureListener {
      def operationComplete(future: ChannelGroupFuture) {
        channels.close()
        inboundBootstrap.releaseExternalResources()
      }
    })
  }

}

