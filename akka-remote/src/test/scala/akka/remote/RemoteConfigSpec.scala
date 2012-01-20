package akka.remote

import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec("") {

  "RemoteExtension" must {
    "be able to parse remote and cluster config elements" in {

      val config = system.settings.config
      import config._

      //akka.remote
      getString("akka.remote.transport") must equal("akka.remote.netty.NettyRemoteSupport")
      getString("akka.remote.secure-cookie") must equal("")
      getBoolean("akka.remote.use-passive-connections") must equal(true)
      getMilliseconds("akka.remote.backoff-timeout") must equal(0)
      // getMilliseconds("akka.remote.remote-daemon-ack-timeout") must equal(30 * 1000)

      //akka.remote.server
      getInt("akka.remote.server.port") must equal(2552)
      getBytes("akka.remote.server.message-frame-size") must equal(1048576L)

      getBoolean("akka.remote.server.require-cookie") must equal(false)
      getBoolean("akka.remote.server.untrusted-mode") must equal(false)
      getInt("akka.remote.server.backlog") must equal(4096)

      getMilliseconds("akka.remote.server.execution-pool-keepalive") must equal(60 * 1000)

      getInt("akka.remote.server.execution-pool-size") must equal(4)

      getBytes("akka.remote.server.max-channel-memory-size") must equal(0)
      getBytes("akka.remote.server.max-total-memory-size") must equal(0)

      //akka.remote.client
      getMilliseconds("akka.remote.client.reconnect-delay") must equal(5 * 1000)
      getMilliseconds("akka.remote.client.read-timeout") must equal(3600 * 1000)
      getMilliseconds("akka.remote.client.reconnection-time-window") must equal(600 * 1000)
      getMilliseconds("akka.remote.client.connection-timeout") must equal(10000)

      // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
      //akka.cluster
      getStringList("akka.cluster.seed-nodes") must equal(new java.util.ArrayList[String])
    }
  }
}
