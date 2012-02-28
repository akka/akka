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

class LeaderElectionSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
    actor.debug.lifecycle = on
    actor.debug.autoreceive = on
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
    "A cluster of three nodes" must {

      // ======= NODE 1 ========
      system1 = ActorSystem("system1", ConfigFactory
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
      val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
      node1 = new Node(system1)
      val fd1 = node1.failureDetector
      val address1 = node1.self.address

      // ======= NODE 2 ========
      system2 = ActorSystem("system2", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty {
              hostname = localhost
              port = 5551
            }
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
      node2 = new Node(system2)
      val fd2 = node2.failureDetector
      val address2 = node2.self.address

      // ======= NODE 3 ========
      system3 = ActorSystem("system3", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty {
              hostname = localhost
              port=5552
            }
            cluster.node-to-join = "akka://system1@localhost:5550"
          }""")
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote3 = system3.provider.asInstanceOf[RemoteActorRefProvider]
      node3 = new Node(system3)
      val fd3 = node3.failureDetector
      val address3 = node3.self.address

      "be able to 'elect' a single leader" taggedAs LongRunningTest in {

        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // let them gossip for 30 seconds

        // check cluster convergence
        node1.convergence must be('defined)
        node2.convergence must be('defined)
        node3.convergence must be('defined)

        // check leader
        node1.isLeader must be(true)
        node2.isLeader must be(false)
        node3.isLeader must be(false)
      }

      "be able to 're-elect' a single leader after leader has left" taggedAs LongRunningTest in {

        // shut down system1 - the leader
        node1.shutdown()
        system1.shutdown()

        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // give them 30 seconds to detect failure of system3

        // check cluster convergence
        node2.convergence must be('defined)
        node3.convergence must be('defined)

        // check leader
        node2.isLeader must be(true)
        node3.isLeader must be(false)
      }

      "be able to 're-elect' a single leader after leader has left (again, leaving a single node)" taggedAs LongRunningTest in {

        // shut down system1 - the leader
        node2.shutdown()
        system2.shutdown()

        println("Give the system time to converge...")
        Thread.sleep(30.seconds.dilated.toMillis) // give them 30 seconds to detect failure of system3

        // check cluster convergence
        node3.convergence must be('defined)

        // check leader
        node3.isLeader must be(true)
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
