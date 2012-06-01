/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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

private[akka] class NettyRemoteServer(val netty: NettyRemoteTransport) {

  import netty.settings

  val ip = InetAddress.getByName(settings.Hostname)

  private val factory =
    settings.UseDispatcherForIO match {
      case Some(id) ⇒
        val d = netty.system.dispatchers.lookup(id)
        new NioServerSocketChannelFactory(d, d)
      case None ⇒
        new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool())
    }

  // group of open channels, used for clean-up
  private val openChannels: ChannelGroup = new DefaultDisposableChannelGroup("akka-remote-server")

  private val bootstrap = {
    val b = new ServerBootstrap(factory)
    b.setPipelineFactory(netty.createPipeline(new RemoteServerHandler(openChannels, netty), false))
    b.setOption("backlog", settings.Backlog)
    b.setOption("tcpNoDelay", true)
    b.setOption("child.keepAlive", true)
    b.setOption("reuseAddress", true)
    if (settings.ReceiveBufferSize.isDefined)
      b.setOption("receiveBufferSize", settings.ReceiveBufferSize.get)
    if (settings.SendBufferSize.isDefined)
      b.setOption("sendBufferSize", settings.SendBufferSize.get)
    if (settings.WriteBufferHighWaterMark.isDefined)
      b.setOption("writeBufferHighWaterMark", settings.WriteBufferHighWaterMark.get)
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

@ChannelHandler.Sharable
private[akka] class RemoteServerAuthenticationHandler(secureCookie: Option[String]) extends SimpleChannelUpstreamHandler {
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
private[akka] class RemoteServerHandler(
  val openChannels: ChannelGroup,
  val netty: NettyRemoteTransport) extends SimpleChannelUpstreamHandler {

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
    netty.notifyListeners(RemoteServerClientDisconnected(netty, ChannelAddress.get(ctx.getChannel)))
  }

  override def channelClosed(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val address = ChannelAddress.get(ctx.getChannel)
    if (address.isDefined && settings.UsePassiveConnections)
      netty.unbindClient(address.get)

    netty.notifyListeners(RemoteServerClientClosed(netty, address))
    ChannelAddress.remove(ctx.getChannel)
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
            ChannelAddress.set(event.getChannel, Option(inbound))

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

