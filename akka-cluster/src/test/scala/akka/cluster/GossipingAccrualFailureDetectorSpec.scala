/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

import java.net.InetSocketAddress

class GossipingAccrualFailureDetectorSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
    cluster.failure-detector.threshold = 3
    actor.debug.lifecycle = on
    actor.debug.autoreceive = on
  }
  """) with ImplicitSender {

  var gossiper1: Gossiper = _
  var gossiper2: Gossiper = _
  var gossiper3: Gossiper = _

  var node1: ActorSystemImpl = _
  var node2: ActorSystemImpl = _
  var node3: ActorSystemImpl = _

  try {
    "A Gossip-driven Failure Detector" must {

      // ======= NODE 1 ========
      node1 = ActorSystem("node1", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty {
              hostname = localhost
              port=5550
            }
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote1 = node1.provider.asInstanceOf[RemoteActorRefProvider]
      gossiper1 = Gossiper(node1, remote1)
      val fd1 = gossiper1.failureDetector
      val address1 = gossiper1.self.address

      // ======= NODE 2 ========
      node2 = ActorSystem("node2", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty {
              hostname = localhost
              port = 5551
            }
            cluster.node-to-join = "akka://node1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote2 = node2.provider.asInstanceOf[RemoteActorRefProvider]
      gossiper2 = Gossiper(node2, remote2)
      val fd2 = gossiper2.failureDetector
      val address2 = gossiper2.self.address

      // ======= NODE 3 ========
      node3 = ActorSystem("node3", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty {
              hostname = localhost
              port=5552
            }
            cluster.node-to-join = "akka://node1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote3 = node3.provider.asInstanceOf[RemoteActorRefProvider]
      gossiper3 = Gossiper(node3, remote3)
      val fd3 = gossiper3.failureDetector
      val address3 = gossiper3.self.address

      "receive gossip heartbeats so that all healthy nodes in the cluster are marked 'available'" in {
        println("Let the nodes gossip for a while...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds
        fd1.isAvailable(address2) must be(true)
        fd1.isAvailable(address3) must be(true)
        fd2.isAvailable(address1) must be(true)
        fd2.isAvailable(address3) must be(true)
        fd3.isAvailable(address1) must be(true)
        fd3.isAvailable(address2) must be(true)
      }

      "mark node as 'unavailable' if a node in the cluster is shut down (and its heartbeats stops)" in {
        // shut down node3
        gossiper3.shutdown()
        node3.shutdown()
        println("Give the remaning nodes time to detect failure...")
        Thread.sleep(30.seconds.dilated.toMillis) // give them 30 seconds to detect failure of node3
        fd1.isAvailable(address2) must be(true)
        fd1.isAvailable(address3) must be(false)
        fd2.isAvailable(address1) must be(true)
        fd2.isAvailable(address3) must be(false)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    gossiper1.shutdown()
    node1.shutdown()

    gossiper2.shutdown()
    node2.shutdown()

    gossiper3.shutdown()
    node3.shutdown()
  }
}
