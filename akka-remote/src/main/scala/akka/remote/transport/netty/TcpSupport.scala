package akka.remote.transport.netty

import akka.actor.Address
import akka.remote.transport.AssociationHandle
import akka.remote.transport.AssociationHandle.{ HandleEvent, HandleEventListener, Disassociated, InboundPayload }
import akka.remote.transport.Transport.{ AssociationEventListener, Status }
import akka.util.ByteString
import java.net.InetSocketAddress
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import org.jboss.netty.channel._
import scala.concurrent.{ Future, Promise }

private[remote] object ChannelLocalActor extends ChannelLocal[Option[HandleEventListener]] {
  override def initialValue(channel: Channel): Option[HandleEventListener] = None
  def notifyListener(channel: Channel, msg: HandleEvent): Unit = get(channel) foreach { _ notify msg }
}

private[remote] trait TcpHandlers extends CommonHandlers {

  import ChannelLocalActor._

  override def registerListener(channel: Channel,
                                listener: HandleEventListener,
                                msg: ChannelBuffer,
                                remoteSocketAddress: InetSocketAddress): Unit = ChannelLocalActor.set(channel, Some(listener))

  override def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle =
    new TcpAssociationHandle(localAddress, remoteAddress, channel)

  override def onDisconnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    notifyListener(e.getChannel, Disassociated)
  }

  override def onMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    notifyListener(e.getChannel, InboundPayload(ByteString(e.getMessage.asInstanceOf[ChannelBuffer].array())))
  }

  override def onException(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    notifyListener(e.getChannel, Disassociated)
    e.getChannel.close() // No graceful close here
  }
}

private[remote] class TcpServerHandler(_transport: NettyTransport, _associationListenerFuture: Future[AssociationEventListener])
  extends ServerHandler(_transport, _associationListenerFuture) with TcpHandlers {

  override def onConnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    initInbound(e.getChannel, e.getChannel.getRemoteAddress, null)
  }

}

private[remote] class TcpClientHandler(_transport: NettyTransport, _statusPromise: Promise[Status])
  extends ClientHandler(_transport, _statusPromise) with TcpHandlers {

  override def onConnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    initOutbound(e.getChannel, e.getChannel.getRemoteAddress, null)
  }

}

private[remote] class TcpAssociationHandle(val localAddress: Address, val remoteAddress: Address, private val channel: Channel)
  extends AssociationHandle {

  override val readHandlerPromise: Promise[HandleEventListener] = Promise()

  override def write(payload: ByteString): Boolean = if (channel.isWritable && channel.isOpen) {
    channel.write(ChannelBuffers.wrappedBuffer(payload.asByteBuffer))
    true
  } else false

  override def disassociate(): Unit = NettyTransport.gracefulClose(channel)
}
