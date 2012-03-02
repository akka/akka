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
    actor.debug.lifecycle = on
    actor.debug.autoreceive = on
    actor.provider = akka.remote.RemoteActorRefProvider
    remote.netty.hostname = localhost
    cluster.failure-detector.threshold = 3
  }
  """) with ImplicitSender {

  var node1: Node = _
  var node2: Node = _
  var node3: Node = _

  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _
  var system3: ActorSystemImpl = _

  try {
    "A Gossip-driven Failure Detector" must {

      // ======= NODE 1 ========
      system1 = ActorSystem("system1", ConfigFactory
        .parseString("akka.remote.netty.port=5550")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
      node1 = Node(system1)
      val fd1 = node1.failureDetector
      val address1 = node1.self.address

      // ======= NODE 2 ========
      system2 = ActorSystem("system2", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port=5551
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
      node2 = Node(system2)
      val fd2 = node2.failureDetector
      val address2 = node2.self.address

      // ======= NODE 3 ========
      system3 = ActorSystem("system3", ConfigFactory
        .parseString("""
          akka {
            remote.netty.port=5552
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote3 = system3.provider.asInstanceOf[RemoteActorRefProvider]
      node3 = Node(system3)
      val fd3 = node3.failureDetector
      val address3 = node3.self.address

      "receive gossip heartbeats so that all healthy systems in the cluster are marked 'available'" taggedAs LongRunningTest in {
        println("Let the systems gossip for a while...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds
        fd1.isAvailable(address2) must be(true)
        fd1.isAvailable(address3) must be(true)
        fd2.isAvailable(address1) must be(true)
        fd2.isAvailable(address3) must be(true)
        fd3.isAvailable(address1) must be(true)
        fd3.isAvailable(address2) must be(true)
      }

      "mark system as 'unavailable' if a system in the cluster is shut down (and its heartbeats stops)" taggedAs LongRunningTest in {
        // shut down system3
        node3.shutdown()
        system3.shutdown()
        println("Give the remaning systems time to detect failure...")
        Thread.sleep(30.seconds.dilated.toMillis) // give them 30 seconds to detect failure of system3
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
    if (node1 ne null) node1.shutdown()
    if (system1 ne null) system1.shutdown()

    if (node2 ne null) node2.shutdown()
    if (system2 ne null) system2.shutdown()

    if (node3 ne null) node3.shutdown()
    if (system3 ne null) system3.shutdown()
  }
}
