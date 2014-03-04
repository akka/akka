/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.actor.ExtendedActorSystem
import scala.concurrent.duration._
import akka.remote.transport.AkkaProtocolSettings
import akka.util.{ Timeout, Helpers }
import akka.remote.transport.netty.{ NettyTransportSettings, SSLSettings }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.tcp.port = 0
  """) {

  "Remoting" must {

    "contain correct configuration values in reference.conf" in {
      val remoteSettings = RARP(system).provider.remoteSettings
      import remoteSettings._

      LogReceive must be(false)
      LogSend must be(false)
      UntrustedMode must be(false)
      LogRemoteLifecycleEvents must be(true)
      ShutdownTimeout.duration must be(10 seconds)
      FlushWait must be(2 seconds)
      StartupTimeout.duration must be(10 seconds)
      RetryGateClosedFor must be(Duration.Zero)
      UnknownAddressGateClosedFor must be(1 minute)
      Dispatcher must be === ""
      UsePassiveConnections must be(true)
      MaximumRetriesInWindow must be(3)
      RetryWindow must be(60 seconds)
      BackoffPeriod must be(10 millis)
      SysMsgAckTimeout must be(1 second)
      SysResendTimeout must be(1 second)
      SysMsgBufferSize must be(1000)
      QuarantineDuration must be(60 seconds)
      CommandAckTimeout.duration must be(30 seconds)
      Transports.size must be(1)
      Transports.head._1 must be(classOf[akka.remote.transport.netty.NettyTransport].getName)
      Transports.head._2 must be(Nil)
      Adapters must be(Map(
        "gremlin" -> classOf[akka.remote.transport.FailureInjectorProvider].getName,
        "trttl" -> classOf[akka.remote.transport.ThrottlerProvider].getName))

      WatchFailureDetectorImplementationClass must be(classOf[PhiAccrualFailureDetector].getName)
      WatchHeartBeatInterval must be(1 seconds)
      WatchHeartbeatExpectedResponseAfter must be(3 seconds)
      WatchUnreachableReaperInterval must be(1 second)
      WatchFailureDetectorConfig.getDouble("threshold") must be(10.0 plusOrMinus 0.0001)
      WatchFailureDetectorConfig.getInt("max-sample-size") must be(200)
      Duration(WatchFailureDetectorConfig.getMilliseconds("acceptable-heartbeat-pause"), MILLISECONDS) must be(4 seconds)
      Duration(WatchFailureDetectorConfig.getMilliseconds("min-std-deviation"), MILLISECONDS) must be(100 millis)

      remoteSettings.config.getString("akka.remote.log-frame-size-exceeding") must be("off")
    }

    "be able to parse AkkaProtocol related config elements" in {
      val settings = new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config)
      import settings._

      RequireCookie must be(false)
      SecureCookie must be === None

      TransportFailureDetectorImplementationClass must be(classOf[PhiAccrualFailureDetector].getName)
      TransportHeartBeatInterval must be === 1.seconds
      TransportFailureDetectorConfig.getDouble("threshold") must be(7.0 plusOrMinus 0.0001)
      TransportFailureDetectorConfig.getInt("max-sample-size") must be(100)
      Duration(TransportFailureDetectorConfig.getMilliseconds("acceptable-heartbeat-pause"), MILLISECONDS) must be(3 seconds)
      Duration(TransportFailureDetectorConfig.getMilliseconds("min-std-deviation"), MILLISECONDS) must be(100 millis)

    }

    "contain correct netty.tcp values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.netty.tcp")
      val s = new NettyTransportSettings(c)
      import s._

      ConnectionTimeout must be === 15.seconds
      WriteBufferHighWaterMark must be === None
      WriteBufferLowWaterMark must be === None
      SendBufferSize must be === Some(256000)
      ReceiveBufferSize must be === Some(256000)
      MaxFrameSize must be === 128000
      Backlog must be === 4096
      TcpNodelay must be(true)
      TcpKeepalive must be(true)
      TcpReuseAddr must be(!Helpers.isWindows)
      c.getString("hostname") must be === ""
      ServerSocketWorkerPoolSize must be === 2
      ClientSocketWorkerPoolSize must be === 2
    }

    "contain correct socket worker pool configuration values in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.netty.tcp")

      // server-socket-worker-pool
      {
        val pool = c.getConfig("server-socket-worker-pool")
        pool.getInt("pool-size-min") must equal(2)

        pool.getDouble("pool-size-factor") must equal(1.0)
        pool.getInt("pool-size-max") must equal(2)
      }

      // client-socket-worker-pool
      {
        val pool = c.getConfig("client-socket-worker-pool")
        pool.getInt("pool-size-min") must equal(2)
        pool.getDouble("pool-size-factor") must equal(1.0)
        pool.getInt("pool-size-max") must equal(2)
      }

    }

    "contain correct ssl configuration values in reference.conf" in {
      val sslSettings = new SSLSettings(system.settings.config.getConfig("akka.remote.netty.ssl.security"))
      sslSettings.SSLKeyStore must be(Some("keystore"))
      sslSettings.SSLKeyStorePassword must be(Some("changeme"))
      sslSettings.SSLKeyPassword must be(Some("changeme"))
      sslSettings.SSLTrustStore must be(Some("truststore"))
      sslSettings.SSLTrustStorePassword must be(Some("changeme"))
      sslSettings.SSLProtocol must be(Some("TLSv1"))
      sslSettings.SSLEnabledAlgorithms must be(Set("TLS_RSA_WITH_AES_128_CBC_SHA"))
      sslSettings.SSLRandomNumberGenerator must be(None)
    }

    "have debug logging of the failure injector turned off in reference.conf" in {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.gremlin")
      c.getBoolean("debug") must be(false)
    }
  }
}
