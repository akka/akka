/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.actor.ExtendedActorSystem
import scala.concurrent.duration._
import akka.remote.transport.AkkaProtocolSettings
import akka.util.{ Timeout, Helpers }
import akka.util.Helpers.ConfigOps
import akka.remote.transport.netty.{ NettyTransportSettings, SSLSettings }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.tcp.port = 0
  """) {

  "Remoting" should {

    "contain correct configuration values in reference.conf" in {
      val remoteSettings = RARP(system).provider.remoteSettings
      import remoteSettings._

      LogReceive should be(false)
      LogSend should be(false)
      UntrustedMode should be(false)
      TrustedSelectionPaths should be(Set.empty[String])
      LogRemoteLifecycleEvents should be(true)
      ShutdownTimeout.duration should be(10 seconds)
      FlushWait should be(2 seconds)
      StartupTimeout.duration should be(10 seconds)
      RetryGateClosedFor should be(5 seconds)
      Dispatcher should be("akka.remote.default-remote-dispatcher")
      UsePassiveConnections should be(true)
      BackoffPeriod should be(5 millis)
      LogBufferSizeExceeding should be(50000)
      SysMsgAckTimeout should be(0.3 seconds)
      SysResendTimeout should be(2 seconds)
      SysResendLimit should be(200)
      SysMsgBufferSize should be(20000)
      InitialSysMsgDeliveryTimeout should be(3 minutes)
      QuarantineDuration should be(5 days)
      CommandAckTimeout.duration should be(30 seconds)
      Transports.size should be(1)
      Transports.head._1 should be(classOf[akka.remote.transport.netty.NettyTransport].getName)
      Transports.head._2 should be(Nil)
      Adapters should be(Map(
        "gremlin" -> classOf[akka.remote.transport.FailureInjectorProvider].getName,
        "trttl" -> classOf[akka.remote.transport.ThrottlerProvider].getName))

      WatchFailureDetectorImplementationClass should be(classOf[PhiAccrualFailureDetector].getName)
      WatchHeartBeatInterval should be(1 seconds)
      WatchHeartbeatExpectedResponseAfter should be(3 seconds)
      WatchUnreachableReaperInterval should be(1 second)
      WatchFailureDetectorConfig.getDouble("threshold") should be(10.0 +- 0.0001)
      WatchFailureDetectorConfig.getInt("max-sample-size") should be(200)
      WatchFailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should be(10 seconds)
      WatchFailureDetectorConfig.getMillisDuration("min-std-deviation") should be(100 millis)

      remoteSettings.config.getString("akka.remote.log-frame-size-exceeding") should be("off")
    }

    "be able to parse AkkaProtocol related config elements" in {
      val settings = new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config)
      import settings._

      RequireCookie should be(false)
      SecureCookie should be(None)

      TransportFailureDetectorImplementationClass should be(classOf[DeadlineFailureDetector].getName)
      TransportHeartBeatInterval should be(4.seconds)
      TransportFailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should be(20 seconds)

    }

    "contain correct netty.tcp values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.netty.tcp")
      val s = new NettyTransportSettings(c)
      import s._

      ConnectionTimeout should ===(15.seconds)
      ConnectionTimeout should ===(new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config)
        .HandshakeTimeout)
      WriteBufferHighWaterMark should be(None)
      WriteBufferLowWaterMark should be(None)
      SendBufferSize should be(Some(256000))
      ReceiveBufferSize should be(Some(256000))
      MaxFrameSize should be(128000)
      Backlog should be(4096)
      TcpNodelay should be(true)
      TcpKeepalive should be(true)
      TcpReuseAddr should be(!Helpers.isWindows)
      c.getString("hostname") should be("")
      ServerSocketWorkerPoolSize should be(2)
      ClientSocketWorkerPoolSize should be(2)
    }

    "contain correct socket worker pool configuration values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.netty.tcp")

      // server-socket-worker-pool
      {
        val pool = c.getConfig("server-socket-worker-pool")
        pool.getInt("pool-size-min") should be(2)

        pool.getDouble("pool-size-factor") should be(1.0)
        pool.getInt("pool-size-max") should be(2)
      }

      // client-socket-worker-pool
      {
        val pool = c.getConfig("client-socket-worker-pool")
        pool.getInt("pool-size-min") should be(2)
        pool.getDouble("pool-size-factor") should be(1.0)
        pool.getInt("pool-size-max") should be(2)
      }

    }

    "contain correct ssl configuration values in reference.conf" in {
      val sslSettings = new SSLSettings(system.settings.config.getConfig("akka.remote.netty.ssl.security"))
      sslSettings.SSLKeyStore should be(Some("keystore"))
      sslSettings.SSLKeyStorePassword should be(Some("changeme"))
      sslSettings.SSLKeyPassword should be(Some("changeme"))
      sslSettings.SSLTrustStore should be(Some("truststore"))
      sslSettings.SSLTrustStorePassword should be(Some("changeme"))
      sslSettings.SSLProtocol should be(Some("TLSv1"))
      sslSettings.SSLEnabledAlgorithms should be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      sslSettings.SSLRandomNumberGenerator should be(None)
    }

    "have debug logging of the failure injector turned off in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.gremlin")
      c.getBoolean("debug") should be(false)
    }
  }
}
