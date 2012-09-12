package akka.remote.transport.netty

import akka.actor.{ Address, ActorRef }
import akka.remote.transport.AssociationHandle
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }
import akka.remote.transport.Transport.Status
import akka.util.ByteString
import java.net.InetSocketAddress
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import org.jboss.netty.channel._
import scala.concurrent.{ Future, Promise }

object ChannelLocalActor extends ChannelLocal[Option[ActorRef]] {
  override def initialValue(channel: Channel): Option[ActorRef] = None
  def trySend(channel: Channel, msg: Any): Unit = get(channel) foreach { _ ! msg }
}

trait TcpHandlers extends CommonHandlers with HasTransport {

  import ChannelLocalActor._

  override def registerReader(channel: Channel,
                              readerRef: ActorRef,
                              msg: ChannelBuffer,
                              remoteSocketAddress: InetSocketAddress): Unit = ChannelLocalActor.set(channel, Some(readerRef))

  override def createHandle(channel: Channel, localAddress: Address, remoteAddress: Address): AssociationHandle =
    new TcpAssociationHandle(localAddress, remoteAddress, channel)

  override def onDisconnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    trySend(e.getChannel, Disassociated)
  }

  override def onMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    trySend(e.getChannel, InboundPayload(ByteString(e.getMessage.asInstanceOf[ChannelBuffer].array())))
  }

  override def onException(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    trySend(e.getChannel, Disassociated)
    e.getChannel.close() // No graceful close here -- force TCP reset
  }
}

class TcpServerHandler(_transport: NettyTransport, _associationHandlerFuture: Future[ActorRef])
  extends ServerHandler(_transport, _associationHandlerFuture) with TcpHandlers {

  override def onConnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    initInbound(e.getChannel, e.getChannel.getRemoteAddress, null)
  }

}

class TcpClientHandler(_transport: NettyTransport, _statusPromise: Promise[Status])
  extends ClientHandler(_transport, _statusPromise) with TcpHandlers {

  override def onConnect(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    initOutbound(e.getChannel, e.getChannel.getRemoteAddress, null)
  }

}

class TcpAssociationHandle(val localAddress: Address, val remoteAddress: Address, private val channel: Channel)
  extends AssociationHandle {

  override val readHandlerPromise: Promise[ActorRef] = Promise()

  override def write(payload: ByteString): Boolean = if (channel.isWritable && channel.isOpen) {
    channel.write(ChannelBuffers.wrappedBuffer(payload.asByteBuffer))
    true
  } else false

  override def disassociate(): Unit = NettyTransport.gracefulClose(channel)
}
