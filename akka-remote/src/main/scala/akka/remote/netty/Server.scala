/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.netty

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import scala.Option.option2Iterable
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.ChannelHandler.Sharable
import org.jboss.netty.channel.group.ChannelGroup
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.frame.{ LengthFieldPrepender, LengthFieldBasedFrameDecoder }
import org.jboss.netty.handler.execution.ExecutionHandler
import akka.event.Logging
import akka.remote.RemoteProtocol.{ RemoteControlProtocol, CommandType, AkkaRemoteProtocol }
import akka.remote.{ RemoteServerShutdown, RemoteServerError, RemoteServerClientDisconnected, RemoteServerClientConnected, RemoteServerClientClosed, RemoteProtocol, RemoteMessage }
import akka.actor.Address
import java.net.InetAddress
import akka.actor.ActorSystemImpl
import org.jboss.netty.channel._

class NettyRemoteServer(val netty: NettyRemoteTransport) {

  import netty.settings

  val ip = InetAddress.getByName(settings.Hostname)

  private val factory = new NioServerSocketChannelFactory(
    Executors.newCachedThreadPool(netty.system.threadFactory),
    Executors.newCachedThreadPool(netty.system.threadFactory))

  private val executionHandler = new ExecutionHandler(netty.executor)

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  private val bootstrap = {
    val b = new ServerBootstrap(factory)
    b.setPipelineFactory(new RemoteServerPipelineFactory(openChannels, executionHandler, netty))
    b.setOption("backlog", settings.Backlog)
    b.setOption("tcpNoDelay", true)
    b.setOption("child.keepAlive", true)
    b.setOption("reuseAddress", true)
    b
  }

  @volatile
  private[akka] var channel: Channel = _

  def start(): Unit = {
    channel = bootstrap.bind(new InetSocketAddress(ip, settings.PortSelector))
    openChannels.add(channel)
  }

  def shutdown() {
    try {
      val shutdownSignal = {
        val b = RemoteControlProtocol.newBuilder.setCommandType(CommandType.SHUTDOWN)
        b.setOrigin(RemoteProtocol.AddressProtocol.newBuilder
          .setSystem(netty.address.system)
          .setHostname(netty.address.host.get)
          .setPort(netty.address.port.get)
          .build)
        if (settings.SecureCookie.nonEmpty)
          b.setCookie(settings.SecureCookie.get)
        b.build
      }
      openChannels.write(netty.createControlEnvelope(shutdownSignal)).awaitUninterruptibly
      openChannels.disconnect
      openChannels.close.awaitUninterruptibly
      bootstrap.releaseExternalResources()
      netty.notifyListeners(RemoteServerShutdown(netty))
    } catch {
      case e: Exception ⇒ netty.notifyListeners(RemoteServerError(e, netty))
    }
  }
}

class RemoteServerPipelineFactory(
  val openChannels: ChannelGroup,
  val executionHandler: ExecutionHandler,
  val netty: NettyRemoteTransport) extends ChannelPipelineFactory {

  import netty.settings

  def getPipeline: ChannelPipeline = {
    val lenDec = new LengthFieldBasedFrameDecoder(settings.MessageFrameSize, 0, 4, 0, 4)
    val lenPrep = new LengthFieldPrepender(4)
    val messageDec = new RemoteMessageDecoder
    val messageEnc = new RemoteMessageEncoder(netty)

    val authenticator = if (settings.RequireCookie) new RemoteServerAuthenticationHandler(settings.SecureCookie) :: Nil else Nil
    val remoteServer = new RemoteServerHandler(openChannels, netty)
    val stages: List[ChannelHandler] = lenDec :: messageDec :: lenPrep :: messageEnc :: executionHandler :: authenticator ::: remoteServer :: Nil
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

object ChannelLocalSystem extends ChannelLocal[ActorSystemImpl] {
  override def initialValue(ch: Channel): ActorSystemImpl = null
}

@ChannelHandler.Sharable
class RemoteServerHandler(
  val openChannels: ChannelGroup,
  val netty: NettyRemoteTransport) extends SimpleChannelUpstreamHandler {

  val channelAddress = new ChannelLocal[Option[Address]](false) {
    override def initialValue(channel: Channel) = None
  }

  import netty.settings

  private var addressToSet = true

  // TODO look into moving that into onBind or similar, but verify that that is guaranteed to be the first to be called
  override def handleUpstream(ctx: ChannelHandlerContext, event: ChannelEvent) = {
    if (addressToSet) {
      netty.setAddressFromChannel(event.getChannel)
      addressToSet = false
    }
    super.handleUpstream(ctx, event)
  }

  /**
   * ChannelOpen overridden to store open channels for a clean postStop of a node.
   * If a channel is closed before, it is automatically removed from the open channels group.
   */
  override def channelOpen(ctx: ChannelHandlerContext, event: ChannelStateEvent) = openChannels.add(ctx.getChannel)

  // TODO might want to log or otherwise signal that a TCP connection has been established here.
  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = ()

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    netty.notifyListeners(RemoteServerClientDisconnected(netty, channelAddress.get(ctx.getChannel)))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val address = channelAddress.get(ctx.getChannel)
    if (address.isDefined && settings.UsePassiveConnections)
      netty.unbindClient(address.get)

    netty.notifyListeners(RemoteServerClientClosed(netty, address))
    channelAddress.remove(ctx.getChannel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = try {
    event.getMessage match {
      case remote: AkkaRemoteProtocol if remote.hasMessage ⇒
        netty.receiveMessage(new RemoteMessage(remote.getMessage, netty.system))

      case remote: AkkaRemoteProtocol if remote.hasInstruction ⇒
        val instruction = remote.getInstruction
        instruction.getCommandType match {
          case CommandType.CONNECT ⇒
            val origin = instruction.getOrigin
            val inbound = Address("akka", origin.getSystem, origin.getHostname, origin.getPort)
            channelAddress.set(event.getChannel, Option(inbound))

            //If we want to reuse the inbound connections as outbound we need to get busy
            if (settings.UsePassiveConnections)
              netty.bindClient(inbound, new PassiveRemoteClient(event.getChannel, netty, inbound))

            netty.notifyListeners(RemoteServerClientConnected(netty, Option(inbound)))
          case CommandType.SHUTDOWN  ⇒ //Will be unbound in channelClosed
          case CommandType.HEARTBEAT ⇒ //Other guy is still alive
          case _                     ⇒ //Unknown command
        }
      case _ ⇒ //ignore
    }
  } catch {
    case e: Exception ⇒ netty.notifyListeners(RemoteServerError(e, netty))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, event: ExceptionEvent) = {
    netty.notifyListeners(RemoteServerError(event.getCause, netty))
    event.getChannel.close()
  }
}

