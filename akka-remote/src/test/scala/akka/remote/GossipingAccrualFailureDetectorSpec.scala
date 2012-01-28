/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import com.typesafe.config._

class GossipingAccrualFailureDetectorSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
    actor.provider = "akka.remote.RemoteActorRefProvider"

    remote.server.hostname = localhost
    remote.server.port = 5550
    remote.failure-detector.threshold = 3
    cluster.seed-nodes = ["akka://localhost:5551"]
  }
  """) with ImplicitSender {

  val conn1 = RemoteNettyAddress("localhost", 5551)
  val node1 = ActorSystem("GossiperSpec", ConfigFactory
    .parseString("akka { remote.server.port=5551, cluster.use-cluster = on }")
    .withFallback(system.settings.config))
  val remote1 =
    node1.asInstanceOf[ActorSystemImpl]
      .provider.asInstanceOf[RemoteActorRefProvider]
      .remote
  val gossiper1 = remote1.gossiper
  val fd1 = remote1.failureDetector
  gossiper1 must be('defined)

  val conn2 = RemoteNettyAddress("localhost", 5552)
  val node2 = ActorSystem("GossiperSpec", ConfigFactory
    .parseString("akka { remote.server.port=5552, cluster.use-cluster = on }")
    .withFallback(system.settings.config))
  val remote2 =
    node2.asInstanceOf[ActorSystemImpl]
      .provider.asInstanceOf[RemoteActorRefProvider]
      .remote
  val gossiper2 = remote2.gossiper
  val fd2 = remote2.failureDetector
  gossiper2 must be('defined)

  val conn3 = RemoteNettyAddress("localhost", 5553)
  val node3 = ActorSystem("GossiperSpec", ConfigFactory
    .parseString("akka { remote.server.port=5553, cluster.use-cluster = on }")
    .withFallback(system.settings.config))
  val remote3 =
    node3.asInstanceOf[ActorSystemImpl]
      .provider.asInstanceOf[RemoteActorRefProvider]
      .remote
  val gossiper3 = remote3.gossiper
  val fd3 = remote3.failureDetector
  gossiper3 must be('defined)

  "A Gossip-driven Failure Detector" must {

    "receive gossip heartbeats so that all healthy nodes in the cluster are marked 'available'" ignore {
      Thread.sleep(5000) // let them gossip for 10 seconds
      fd1.isAvailable(conn2) must be(true)
      fd1.isAvailable(conn3) must be(true)
      fd2.isAvailable(conn1) must be(true)
      fd2.isAvailable(conn3) must be(true)
      fd3.isAvailable(conn1) must be(true)
      fd3.isAvailable(conn2) must be(true)
    }

    "mark node as 'unavailable' if a node in the cluster is shut down and its heartbeats stops" ignore {
      // kill node 3
      gossiper3.get.shutdown()
      node3.shutdown()
      Thread.sleep(5000) // let them gossip for 10 seconds

      fd1.isAvailable(conn2) must be(true)
      fd1.isAvailable(conn3) must be(false)
      fd2.isAvailable(conn1) must be(true)
      fd2.isAvailable(conn3) must be(false)
    }
  }

  override def atTermination() {
    gossiper1.get.shutdown()
    gossiper2.get.shutdown()
    gossiper3.get.shutdown()
    node1.shutdown()
    node2.shutdown()
    node3.shutdown()
    // FIXME Ordering problem - If we shut down the ActorSystem before the Gossiper then we get an IllegalStateException
  }
}
