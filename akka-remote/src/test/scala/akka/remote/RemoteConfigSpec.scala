/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import akka.actor.ExtendedActorSystem
import akka.util.duration._
import akka.util.Duration
import akka.remote.netty.NettyRemoteTransport

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
  akka {
    actor.provider = "akka.remote.RemoteActorRefProvider"
    remote.netty.port = 0
  }
  """) {

  "Remoting" must {

    "be able to parse generic remote config elements" in {
      val settings = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].remoteSettings
      import settings._

      RemoteTransport must be("akka.remote.netty.NettyRemoteTransport")
      UntrustedMode must be(false)
      RemoteSystemDaemonAckTimeout must be(30 seconds)
      LogRemoteLifeCycleEvents must be(false)
    }

    "be able to parse Netty config elements" in {
      val settings =
        system.asInstanceOf[ExtendedActorSystem]
          .provider.asInstanceOf[RemoteActorRefProvider]
          .transport.asInstanceOf[NettyRemoteTransport]
          .settings
      import settings._

      BackoffTimeout must be(Duration.Zero)
      SecureCookie must be(None)
      RequireCookie must be(false)
      UsePassiveConnections must be(true)
      Hostname must not be "" // will be set to the local IP
      PortSelector must be(0)
      OutboundLocalAddress must be(None)
      MessageFrameSize must be(1048576)
      ConnectionTimeout must be(2 minutes)
      Backlog must be(4096)
      ExecutionPoolKeepalive must be(1 minute)
      ExecutionPoolSize must be(4)
      MaxChannelMemorySize must be(0)
      MaxTotalMemorySize must be(0)
      ReconnectDelay must be(5 seconds)
      ReadTimeout must be(0 millis)
      WriteTimeout must be(10 seconds)
      AllTimeout must be(0 millis)
      ReconnectionTimeWindow must be(10 minutes)
      ServerSocketWorkerPoolSize must be >= (2)
      ServerSocketWorkerPoolSize must be <= (8)
      ClientSocketWorkerPoolSize must be >= (2)
      ClientSocketWorkerPoolSize must be <= (8)
    }

    "contain correct configuration values in reference.conf" in {
      val c = system.asInstanceOf[ExtendedActorSystem].
        provider.asInstanceOf[RemoteActorRefProvider].
        remoteSettings.config.getConfig("akka.remote.netty")

      // server-socket-worker-pool
      {
        val pool = c.getConfig("server-socket-worker-pool")
        pool.getInt("pool-size-min") must equal(2)
        pool.getDouble("pool-size-factor") must equal(1.0)
        pool.getInt("pool-size-max") must equal(8)
      }

      // client-socket-worker-pool
      {
        val pool = c.getConfig("client-socket-worker-pool")
        pool.getInt("pool-size-min") must equal(2)
        pool.getDouble("pool-size-factor") must equal(1.0)
        pool.getInt("pool-size-max") must equal(8)
      }
    }
  }
}
