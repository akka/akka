package akka.remote.transport.netty

import akka.actor.{ Address }
import akka.remote.transport.AssociationHandle
import akka.remote.transport.AssociationHandle.{ HandleEventListener, InboundPayload }
import akka.remote.transport.Transport.{ AssociationEventListener, Status }
import akka.util.ByteString
import java.net.{ SocketAddress, InetAddress, InetSocketAddress }
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.channel._
import scala.concurrent.{ Future, Promise }

trait UdpHandlers extends CommonHandlers with HasTransport {

  override def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle =
    new UdpAssociationHandle(localAddress, remoteAddress, channel, transport)

  override def registerListener(channel: Channel,
                                listener: HandleEventListener,
                                msg: ChannelBuffer,
                                remoteSocketAddress: InetSocketAddress): Unit = {
    val oldReader: HandleEventListener = transport.udpConnectionTable.putIfAbsent(remoteSocketAddress, listener)
    if (oldReader ne null) {
      throw new NettyTransportException(s"Listener $listener attempted to register for remote address $remoteSocketAddress" +
        s" but $oldReader was already registered.", null)
    }
    listener notify InboundPayload(ByteString(msg.array()))
  }

  override def onMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    if (e.getRemoteAddress.isInstanceOf[InetSocketAddress]) {
      val inetSocketAddress: InetSocketAddress = e.getRemoteAddress.asInstanceOf[InetSocketAddress]
      if (!transport.udpConnectionTable.containsKey(inetSocketAddress)) {
        e.getChannel.setReadable(false)
        initUdp(e.getChannel, e.getRemoteAddress, e.getMessage.asInstanceOf[ChannelBuffer])

      } else {
        val listener = transport.udpConnectionTable.get(inetSocketAddress)
        listener notify InboundPayload(ByteString(e.getMessage.asInstanceOf[ChannelBuffer].array()))
      }
    }
  }

  def initUdp(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit
}

class UdpServerHandler(_transport: NettyTransport, _associationListenerFuture: Future[AssociationEventListener])
  extends ServerHandler(_transport, _associationListenerFuture) with UdpHandlers {

  override def initUdp(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit =
    initInbound(channel, remoteSocketAddress, msg)
}

class UdpClientHandler(_transport: NettyTransport, _statusPromise: Promise[Status])
  extends ClientHandler(_transport, _statusPromise) with UdpHandlers {

  override def initUdp(channel: Channel, remoteSocketAddress: SocketAddress, msg: ChannelBuffer): Unit =
    initOutbound(channel, remoteSocketAddress, msg)
}

class UdpAssociationHandle(val localAddress: Address,
                           val remoteAddress: Address,
                           private val channel: Channel,
                           private val transport: NettyTransport) extends AssociationHandle {

  override val readHandlerPromise: Promise[HandleEventListener] = Promise()

  override def write(payload: ByteString): Boolean = {
    if (!channel.isConnected)
      channel.connect(new InetSocketAddress(InetAddress.getByName(remoteAddress.host.get), remoteAddress.port.get))

    if (channel.isWritable && channel.isOpen) {
      channel.write(ChannelBuffers.wrappedBuffer(payload.asByteBuffer))
      true
    } else false
  }

  override def disassociate(): Unit = {
    channel.close()
    transport.udpConnectionTable.remove(transport.addressToSocketAddress(remoteAddress))
  }
}