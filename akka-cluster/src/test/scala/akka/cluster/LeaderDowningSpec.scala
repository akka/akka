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

class LeaderDowningSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
    actor.provider = "akka.remote.RemoteActorRefProvider"
    cluster {
      failure-detector.threshold = 3
      auto-down = on
    }
  }
  """) with ImplicitSender {

  var node1: Node = _
  var node2: Node = _
  var node3: Node = _
  var node4: Node = _

  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _
  var system3: ActorSystemImpl = _
  var system4: ActorSystemImpl = _

  try {
    "The Leader in a 4 node cluster" must {

      // ======= NODE 1 ========
      system1 = ActorSystem("system1", ConfigFactory
        .parseString("""
          akka {
            remote.netty {
              hostname = localhost
              port=5550
            }
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
      node1 = Node(system1)
      val fd1 = node1.failureDetector
      val address1 = node1.remoteAddress

      // ======= NODE 2 ========
      system2 = ActorSystem("system2", ConfigFactory
        .parseString("""
          akka {
            remote.netty {
              hostname = localhost
              port = 5551
            }
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
      node2 = Node(system2)
      val fd2 = node2.failureDetector
      val address2 = node2.remoteAddress

      // ======= NODE 3 ========
      system3 = ActorSystem("system3", ConfigFactory
        .parseString("""
          akka {
            remote.netty {
              hostname = localhost
              port=5552
            }
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote3 = system3.provider.asInstanceOf[RemoteActorRefProvider]
      node3 = Node(system3)
      val fd3 = node3.failureDetector
      val address3 = node3.remoteAddress

      // ======= NODE 4 ========
      system4 = ActorSystem("system4", ConfigFactory
        .parseString("""
          akka {
            remote.netty {
              hostname = localhost
              port=5553
            }
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote4 = system4.provider.asInstanceOf[RemoteActorRefProvider]
      node4 = Node(system4)
      val fd4 = node4.failureDetector
      val address4 = node4.remoteAddress

      "be able to DOWN a (last) node that is UNREACHABLE" taggedAs LongRunningTest in {

        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds

        // check cluster convergence
        node1.convergence must be('defined)
        node2.convergence must be('defined)
        node3.convergence must be('defined)
        node4.convergence must be('defined)

        // shut down system4
        node4.shutdown()
        system4.shutdown()

        // wait for convergence - e.g. the leader to auto-down the failed node
        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds

        // check cluster convergence
        node1.convergence must be('defined)
        node2.convergence must be('defined)
        node3.convergence must be('defined)

        node1.latestGossip.members.size must be(3)
        node1.latestGossip.members.exists(_.address == address4) must be(false)
      }

      "be able to DOWN a (middle) node that is UNREACHABLE" taggedAs LongRunningTest in {

        // check cluster convergence
        node1.convergence must be('defined)
        node2.convergence must be('defined)
        node3.convergence must be('defined)

        // shut down system4
        node2.shutdown()
        system2.shutdown()

        // wait for convergence - e.g. the leader to auto-down the failed node
        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds

        // check cluster convergence
        node1.convergence must be('defined)
        node3.convergence must be('defined)

        node1.latestGossip.members.size must be(2)
        node1.latestGossip.members.exists(_.address == address4) must be(false)
        node1.latestGossip.members.exists(_.address == address2) must be(false)
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

    if (node4 ne null) node4.shutdown()
    if (system4 ne null) system4.shutdown()
  }
}
