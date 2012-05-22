/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.remote.netty.NettyRemoteTransport
import akka.remote.RemoteSettings
import akka.actor.ActorSystemImpl
import akka.remote.RemoteActorRefProvider
import org.jboss.netty.channel.ChannelHandler
import org.jboss.netty.channel.ChannelPipelineFactory

/**
 * INTERNAL API.
 */
private[akka] class TestConductorTransport(_remoteSettings: RemoteSettings, _system: ActorSystemImpl, _provider: RemoteActorRefProvider)
  extends NettyRemoteTransport(_remoteSettings, _system, _provider) {

  override def createPipeline(endpoint: ⇒ ChannelHandler, withTimeout: Boolean): ChannelPipelineFactory =
    new ChannelPipelineFactory {
      def getPipeline = PipelineFactory(new NetworkFailureInjector(system) +: PipelineFactory.defaultStack(withTimeout) :+ endpoint)
    }

}