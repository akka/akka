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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.tcp.port = 0
  """) {

  // FIXME: These tests are ignored as it tests configuration specific to the old remoting.
  "Remoting" must {

    "be able to parse generic remote config elements" in {
      val settings = RARP(system).provider.remoteSettings
      import settings._

      StartupTimeout must be === Timeout(5.seconds)
      ShutdownTimeout must be === Timeout(5.seconds)
      FlushWait must be === 2.seconds
      UsePassiveConnections must be(true)
      UntrustedMode must be(false)
      LogRemoteLifecycleEvents must be(false)
      LogReceive must be(false)
      LogSend must be(false)
      RetryGateClosedFor must be === 0.seconds
      UnknownAddressGateClosedFor must be === 60.seconds
      MaximumRetriesInWindow must be === 5
      RetryWindow must be === 3.seconds
      BackoffPeriod must be === 10.milliseconds
      CommandAckTimeout must be === Timeout(30.seconds)

    }

    "be able to parse AkkaProtocol related config elements" in {
      val settings = new AkkaProtocolSettings(RARP(system).provider.remoteSettings.config)
      import settings._

      WaitActivityEnabled must be(true)
      FailureDetectorThreshold must be === 7
      FailureDetectorMaxSampleSize must be === 100
      FailureDetectorStdDeviation must be === 100.milliseconds
      AcceptableHeartBeatPause must be === 3.seconds
      HeartBeatInterval must be === 1.seconds
      RequireCookie must be(false)
      SecureCookie must be === ""
    }

    "contain correct configuration values in reference.conf" ignore {
      val c = RARP(system).provider.remoteSettings.config.getConfig("akka.remote.netty.tcp")

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

      {
        c.getString("reuse-address") must be("off-for-windows")
      }
    }
  }
}
