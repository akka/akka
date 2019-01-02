/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.testconductor

import org.jboss.netty.channel.{ Channel, ChannelPipeline, ChannelPipelineFactory, ChannelUpstreamHandler, DefaultChannelPipeline }
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioServerSocketChannelFactory }
import org.jboss.netty.bootstrap.{ ClientBootstrap, ServerBootstrap }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import akka.event.Logging
import akka.util.Helpers
import org.jboss.netty.handler.codec.oneone.{ OneToOneEncoder, OneToOneDecoder }
import org.jboss.netty.channel.ChannelHandlerContext
import akka.protobuf.Message
import org.jboss.netty.buffer.ChannelBuffer

/**
 * INTERNAL API.
 */
private[akka] class ProtobufEncoder extends OneToOneEncoder {
  override def encode(ctx: ChannelHandlerContext, ch: Channel, msg: AnyRef): AnyRef =
    msg match {
      case m: Message ⇒
        val bytes = m.toByteArray()
        ctx.getChannel.getConfig.getBufferFactory.getBuffer(bytes, 0, bytes.length)
      case other ⇒ other
    }
}

/**
 * INTERNAL API.
 */
private[akka] class ProtobufDecoder(prototype: Message) extends OneToOneDecoder {
  override def decode(ctx: ChannelHandlerContext, ch: Channel, obj: AnyRef): AnyRef =
    obj match {
      case buf: ChannelBuffer ⇒
        val len = buf.readableBytes()
        val bytes = new Array[Byte](len)
        buf.getBytes(buf.readerIndex, bytes, 0, len)
        prototype.getParserForType.parseFrom(bytes)
      case other ⇒ other
    }
}

/**
 * INTERNAL API.
 */
private[akka] class TestConductorPipelineFactory(handler: ChannelUpstreamHandler) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val encap = List(new LengthFieldPrepender(4), new LengthFieldBasedFrameDecoder(10000, 0, 4, 0, 4))
    val proto = List(new ProtobufEncoder, new ProtobufDecoder(TestConductorProtocol.Wrapper.getDefaultInstance))
    val msg = List(new MsgEncoder, new MsgDecoder)
    (encap ::: proto ::: msg ::: handler :: Nil).foldLeft(new DefaultChannelPipeline) {
      (pipe, handler) ⇒ pipe.addLast(Logging.simpleName(handler.getClass), handler); pipe
    }
  }
}

/**
 * INTERNAL API.
 */
private[akka] sealed trait Role
/**
 * INTERNAL API.
 */
private[akka] case object Client extends Role
/**
 * INTERNAL API.
 */
private[akka] case object Server extends Role

/**
 * INTERNAL API.
 */
private[akka] object RemoteConnection {
  def apply(role: Role, sockaddr: InetSocketAddress, poolSize: Int, handler: ChannelUpstreamHandler): Channel = {
    role match {
      case Client ⇒
        val socketfactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool,
          poolSize)
        val bootstrap = new ClientBootstrap(socketfactory)
        bootstrap.setPipelineFactory(new TestConductorPipelineFactory(handler))
        bootstrap.setOption("tcpNoDelay", true)
        bootstrap.connect(sockaddr).getChannel
      case Server ⇒
        val socketfactory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool,
          poolSize)
        val bootstrap = new ServerBootstrap(socketfactory)
        bootstrap.setPipelineFactory(new TestConductorPipelineFactory(handler))
        bootstrap.setOption("reuseAddress", !Helpers.isWindows)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.bind(sockaddr)
    }
  }

  def getAddrString(channel: Channel) = channel.getRemoteAddress match {
    case i: InetSocketAddress ⇒ i.toString
    case _                    ⇒ "[unknown]"
  }

  def shutdown(channel: Channel) =
    try channel.close() finally try channel.getFactory.shutdown() finally channel.getFactory.releaseExternalResources()
}
