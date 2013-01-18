/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps

import akka.testkit.AkkaSpec
import akka.actor.ExtendedActorSystem
import scala.concurrent.duration._
import akka.util.Helpers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
  akka {
    actor.provider = "akka.remote.RemoteActorRefProvider"
    remote.netty.port = 0
  }
  """) {

  // FIXME: These tests are ignored as it tests configuration specific to the old remoting.
  "Remoting" must {

    "be able to parse generic remote config elements" ignore {
      val settings = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].remoteSettings
      import settings._

      RemoteTransport must be("akka.remote.netty.NettyRemoteTransport")
      UntrustedMode must be(false)
      RemoteSystemDaemonAckTimeout must be(30 seconds)
      LogRemoteLifeCycleEvents must be(true)
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

      {
        c.getString("reuse-address") must be("off-for-windows")
      }
    }
  }
}
