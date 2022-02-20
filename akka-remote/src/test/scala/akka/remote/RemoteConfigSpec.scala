/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._

import scala.annotation.nowarn
import language.postfixOps

import akka.remote.transport.AkkaProtocolSettings
import akka.remote.transport.netty.{ NettyTransportSettings, SSLSettings }
import akka.testkit.AkkaSpec
import akka.util.Helpers
import akka.util.Helpers.ConfigOps

@nowarn // classic deprecated
class RemoteConfigSpec extends AkkaSpec("""
    akka.actor.provider = remote
    akka.remote.classic.netty.tcp.port = 0
  """) {

  "Remoting" should {

    "contain correct configuration values in reference.conf" in {
      val remoteSettings = RARP(system).provider.remoteSettings
      import remoteSettings._

      LogReceive should ===(false)
      LogSend should ===(false)
      UntrustedMode should ===(false)
      TrustedSelectionPaths should ===(Set.empty[String])
      ShutdownTimeout.duration should ===(10 seconds)
      FlushWait should ===(2 seconds)
      StartupTimeout.duration should ===(10 seconds)
      RetryGateClosedFor should ===(5 seconds)
      Dispatcher should ===("akka.remote.default-remote-dispatcher")
      UsePassiveConnections should ===(true)
      BackoffPeriod should ===(5 millis)
      LogBufferSizeExceeding should ===(50000)
      SysMsgAckTimeout should ===(0.3 seconds)
      SysResendTimeout should ===(2 seconds)
      SysResendLimit should ===(200)
      SysMsgBufferSize should ===(20000)
      InitialSysMsgDeliveryTimeout should ===(3 minutes)
      QuarantineDuration should ===(5 days)
      CommandAckTimeout.duration should ===(30 seconds)
      Transports.size should ===(1)
      Transports.head._1 should ===(classOf[akka.remote.transport.netty.NettyTransport].getName)
      Transports.head._2 should ===(Nil)
      Adapters should ===(
        Map(
          "gremlin" -> classOf[akka.remote.transport.FailureInjectorProvider].getName,
          "trttl" -> classOf[akka.remote.transport.ThrottlerProvider].getName))

      WatchFailureDetectorImplementationClass should ===(classOf[PhiAccrualFailureDetector].getName)
      WatchHeartBeatInterval should ===(1 seconds)
      WatchHeartbeatExpectedResponseAfter should ===(1 seconds)
      WatchUnreachableReaperInterval should ===(1 second)
      WatchFailureDetectorConfig.getDouble("threshold") should ===(10.0 +- 0.0001)
      WatchFailureDetectorConfig.getInt("max-sample-size") should ===(200)
      WatchFailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should ===(10 seconds)
      WatchFailureDetectorConfig.getMillisDuration("min-std-deviation") should ===(100 millis)

      remoteSettings.config.getString("akka.remote.classic.log-frame-size-exceeding") should ===("off")
    }

    "be able to parse AkkaProtocol related config elements" in {
      val settings = new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config)
      import settings._

      TransportFailureDetectorImplementationClass should ===(classOf[DeadlineFailureDetector].getName)
      TransportHeartBeatInterval should ===(4.seconds)
      TransportFailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should ===(120.seconds)

    }

    "contain correct netty.tcp values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.classic.netty.tcp")
      val s = new NettyTransportSettings(c)
      import s._

      ConnectionTimeout should ===(15.seconds)
      ConnectionTimeout should ===(
        new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config).HandshakeTimeout)
      WriteBufferHighWaterMark should ===(None)
      WriteBufferLowWaterMark should ===(None)
      SendBufferSize should ===(Some(256000))
      ReceiveBufferSize should ===(Some(256000))
      MaxFrameSize should ===(128000)
      Backlog should ===(4096)
      TcpNodelay should ===(true)
      TcpKeepalive should ===(true)
      TcpReuseAddr should ===(!Helpers.isWindows)
      c.getString("hostname") should ===("")
      c.getString("bind-hostname") should ===("")
      c.getString("bind-port") should ===("")
      ServerSocketWorkerPoolSize should ===(2)
      ClientSocketWorkerPoolSize should ===(2)
    }

    "contain correct socket worker pool configuration values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.classic.netty.tcp")

      // server-socket-worker-pool
      {
        val pool = c.getConfig("server-socket-worker-pool")
        pool.getInt("pool-size-min") should ===(2)

        pool.getDouble("pool-size-factor") should ===(1.0)
        pool.getInt("pool-size-max") should ===(2)
      }

      // client-socket-worker-pool
      {
        val pool = c.getConfig("client-socket-worker-pool")
        pool.getInt("pool-size-min") should ===(2)
        pool.getDouble("pool-size-factor") should ===(1.0)
        pool.getInt("pool-size-max") should ===(2)
      }

    }

    "contain correct ssl configuration values in reference.conf" in {
      val sslSettings = new SSLSettings(system.settings.config.getConfig("akka.remote.classic.netty.ssl.security"))
      sslSettings.SSLKeyStore should ===("keystore")
      sslSettings.SSLKeyStorePassword should ===("changeme")
      sslSettings.SSLKeyPassword should ===("changeme")
      sslSettings.SSLTrustStore should ===("truststore")
      sslSettings.SSLTrustStorePassword should ===("changeme")
      sslSettings.SSLProtocol should ===("TLSv1.2")
      sslSettings.SSLEnabledAlgorithms should ===(
        Set("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"))
      sslSettings.SSLRandomNumberGenerator should ===("")
    }

    "have debug logging of the failure injector turned off in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.classic.gremlin")
      c.getBoolean("debug") should ===(false)
    }
  }
}
