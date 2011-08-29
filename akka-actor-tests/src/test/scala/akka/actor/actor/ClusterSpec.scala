package akka.actor.actor

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
      getInt("akka.cluster.server.port") must equal(Some(2552))
      getInt("akka.cluster.max-time-to-wait-until-connected") must equal(Some(30))
      getInt("akka.cluster.session-timeout") must equal(Some(60))
      getInt("akka.cluster.connection-timeout") must equal(Some(60))
      getBool("akka.cluster.use-compression") must equal(Some(false))
      getInt("akka.cluster.connection-timeout") must equal(Some(60))
      getInt("akka.cluster.remote-daemon-ack-timeout") must equal(Some(30))
      getBool("akka.cluster.include-ref-node-in-replica-set") must equal(Some(true))
      getString("akka.cluster.compression-scheme") must equal(Some(""))
      getInt("akka.cluster.zlib-compression-level") must equal(Some(6))
      getString("akka.cluster.layer") must equal(Some("akka.cluster.netty.NettyRemoteSupport"))
      getString("akka.cluster.secure-cookie") must equal(Some(""))
      getString("akka.cluster.log-directory") must equal(Some("_akka_cluster"))

      //akka.cluster.replication
      getString("akka.cluster.replication.digest-type") must equal(Some("MAC"))
      getString("akka.cluster.replication.password") must equal(Some("secret"))
      getInt("akka.cluster.replication.ensemble-size") must equal(Some(3))
      getInt("akka.cluster.replication.quorum-size") must equal(Some(2))
      getInt("akka.cluster.replication.snapshot-frequency") must equal(Some(1000))
      getInt("akka.cluster.replication.timeout") must equal(Some(30))

      //akka.cluster.server
      getInt("akka.cluster.server.port") must equal(Some(2552))
      getInt("akka.cluster.server.message-frame-size") must equal(Some(1048576))
      getInt("akka.cluster.server.connection-timeout") must equal(Some(120))
      getBool("akka.cluster.server.require-cookie") must equal(Some(false))
      getBool("akka.cluster.server.untrusted-mode") must equal(Some(false))
      getInt("akka.cluster.server.backlog") must equal(Some(4096))
      getInt("akka.cluster.server.execution-pool-keepalive") must equal(Some(60))
      getInt("akka.cluster.server.execution-pool-size") must equal(Some(16))
      getInt("akka.cluster.server.max-channel-memory-size") must equal(Some(0))
      getInt("akka.cluster.server.max-total-memory-size") must equal(Some(0))

      //akka.cluster.client
      getBool("akka.cluster.client.buffering.retry-message-send-on-failure") must equal(Some(true))
      getInt("akka.cluster.client.buffering.capacity") must equal(Some(-1))
      getInt("akka.cluster.client.reconnect-delay") must equal(Some(5))
      getInt("akka.cluster.client.read-timeout") must equal(Some(3600))
      getInt("akka.cluster.client.reap-futures-delay") must equal(Some(5))
      getInt("akka.cluster.client.reconnection-time-window") must equal(Some(600))
    }
  }
}
