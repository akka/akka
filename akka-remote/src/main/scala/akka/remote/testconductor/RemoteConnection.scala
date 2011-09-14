/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import org.jboss.netty.channel.{ Channel, ChannelPipeline, ChannelPipelineFactory, ChannelUpstreamHandler, SimpleChannelUpstreamHandler, StaticChannelPipeline }
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioServerSocketChannelFactory }
import org.jboss.netty.bootstrap.{ ClientBootstrap, ServerBootstrap }
import org.jboss.netty.handler.codec.frame.{ LengthFieldBasedFrameDecoder, LengthFieldPrepender }
import org.jboss.netty.handler.codec.compression.{ ZlibDecoder, ZlibEncoder }
import org.jboss.netty.handler.codec.protobuf.{ ProtobufDecoder, ProtobufEncoder }
import org.jboss.netty.handler.timeout.{ ReadTimeoutHandler, ReadTimeoutException }
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class TestConductorPipelineFactory(handler: ChannelUpstreamHandler) extends ChannelPipelineFactory {
  def getPipeline: ChannelPipeline = {
    val encap = List(new LengthFieldPrepender(4), new LengthFieldBasedFrameDecoder(10000, 0, 4, 0, 4))
    val proto = List(new ProtobufEncoder, new ProtobufDecoder(TestConductorProtocol.Wrapper.getDefaultInstance))
    new StaticChannelPipeline(encap ::: proto ::: handler :: Nil: _*)
  }
}

sealed trait Role
case object Client extends Role
case object Server extends Role

object RemoteConnection {
  def apply(role: Role, host: String, port: Int, handler: ChannelUpstreamHandler): Channel = {
    val sockaddr = new InetSocketAddress(host, port)
    role match {
      case Client ⇒
        val socketfactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)
        val bootstrap = new ClientBootstrap(socketfactory)
        bootstrap.setPipelineFactory(new TestConductorPipelineFactory(handler))
        bootstrap.setOption("tcpNoDelay", true)
        bootstrap.connect(sockaddr).getChannel
      case Server ⇒
        val socketfactory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool)
        val bootstrap = new ServerBootstrap(socketfactory)
        bootstrap.setPipelineFactory(new TestConductorPipelineFactory(handler))
        bootstrap.setOption("reuseAddress", true)
        bootstrap.setOption("child.tcpNoDelay", true)
        bootstrap.bind(sockaddr)
    }
  }

  def getAddrString(channel: Channel) = channel.getRemoteAddress match {
    case i: InetSocketAddress ⇒ i.toString
    case _                    ⇒ "[unknown]"
  }
}
