package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.config.Config

class ClusterSpec extends WordSpec with MustMatchers {

  "ClusterSpec: A Deployer" must {
    "be able to parse 'akka.actor.cluster._' config elements" in {
      import Config.config._

      //akka.cluster
      getString("akka.cluster.name") must equal(Some("test-cluster"))
      getString("akka.cluster.zookeeper-server-addresses") must equal(Some("localhost:2181"))
      getInt("akka.remote.server.port") must equal(Some(2552))
      getInt("akka.cluster.max-time-to-wait-until-connected") must equal(Some(30))
      getInt("akka.cluster.session-timeout") must equal(Some(60))
      getInt("akka.cluster.connection-timeout") must equal(Some(60))
      getBool("akka.remote.use-compression") must equal(Some(false))
      getInt("akka.cluster.connection-timeout") must equal(Some(60))
      getInt("akka.remote.remote-daemon-ack-timeout") must equal(Some(30))
      getBool("akka.cluster.include-ref-node-in-replica-set") must equal(Some(true))
      getString("akka.remote.compression-scheme") must equal(Some(""))
      getInt("akka.remote.zlib-compression-level") must equal(Some(6))
      getString("akka.remote.layer") must equal(Some("akka.cluster.netty.NettyRemoteSupport"))
      getString("akka.remote.secure-cookie") must equal(Some(""))
      getString("akka.cluster.log-directory") must equal(Some("_akka_cluster"))

      //akka.cluster.replication
      getString("akka.cluster.replication.digest-type") must equal(Some("MAC"))
      getString("akka.cluster.replication.password") must equal(Some("secret"))
      getInt("akka.cluster.replication.ensemble-size") must equal(Some(3))
      getInt("akka.cluster.replication.quorum-size") must equal(Some(2))
      getInt("akka.cluster.replication.snapshot-frequency") must equal(Some(1000))
      getInt("akka.cluster.replication.timeout") must equal(Some(30))

      //akka.remote.server
      getInt("akka.remote.server.port") must equal(Some(2552))
      getInt("akka.remote.server.message-frame-size") must equal(Some(1048576))
      getInt("akka.remote.server.connection-timeout") must equal(Some(120))
      getBool("akka.remote.server.require-cookie") must equal(Some(false))
      getBool("akka.remote.server.untrusted-mode") must equal(Some(false))
      getInt("akka.remote.server.backlog") must equal(Some(4096))
      getInt("akka.remote.server.execution-pool-keepalive") must equal(Some(60))
      getInt("akka.remote.server.execution-pool-size") must equal(Some(16))
      getInt("akka.remote.server.max-channel-memory-size") must equal(Some(0))
      getInt("akka.remote.server.max-total-memory-size") must equal(Some(0))

      //akka.remote.client
      getBool("akka.remote.client.buffering.retry-message-send-on-failure") must equal(Some(false))
      getInt("akka.remote.client.buffering.capacity") must equal(Some(-1))
      getInt("akka.remote.client.reconnect-delay") must equal(Some(5))
      getInt("akka.remote.client.read-timeout") must equal(Some(3600))
      getInt("akka.remote.client.reap-futures-delay") must equal(Some(5))
      getInt("akka.remote.client.reconnection-time-window") must equal(Some(600))
    }
  }
}
