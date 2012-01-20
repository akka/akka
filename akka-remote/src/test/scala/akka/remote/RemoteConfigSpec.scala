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
      getMilliseconds("akka.remote.server.connection-timeout") must equal(120 * 1000)
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

      // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
      //akka.cluster
      getStringList("akka.cluster.seed-nodes") must equal(new java.util.ArrayList[String])

      //   getMilliseconds("akka.cluster.max-time-to-wait-until-connected") must equal(30 * 1000)
      //   getMilliseconds("akka.cluster.session-timeout") must equal(60 * 1000)
      //   getMilliseconds("akka.cluster.connection-timeout") must equal(60 * 1000)
      //   getBoolean("akka.cluster.include-ref-node-in-replica-set") must equal(true)
      //   getString("akka.cluster.log-directory") must equal("_akka_cluster")

      //   //akka.cluster.replication
      //   getString("akka.cluster.replication.digest-type") must equal("MAC")
      //   getString("akka.cluster.replication.password") must equal("secret")
      //   getInt("akka.cluster.replication.ensemble-size") must equal(3)
      //   getInt("akka.cluster.replication.quorum-size") must equal(2)
      //   getInt("akka.cluster.replication.snapshot-frequency") must equal(1000)
      //   getMilliseconds("akka.cluster.replication.timeout") must equal(30 * 1000)
    }
  }
}
