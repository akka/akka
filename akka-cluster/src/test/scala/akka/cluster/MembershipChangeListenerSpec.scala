/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import java.net.InetSocketAddress
import java.util.concurrent.{ CountDownLatch, TimeUnit }

import scala.collection.immutable.SortedSet

import com.typesafe.config._

class MembershipChangeListenerSpec extends AkkaSpec("""
  akka {
    loglevel = "INFO"
  }
  """) with ImplicitSender {

  var gossiper0: Gossiper = _
  var gossiper1: Gossiper = _
  var gossiper2: Gossiper = _

  var node0: ActorSystemImpl = _
  var node1: ActorSystemImpl = _
  var node2: ActorSystemImpl = _

  try {
    "A set of connected cluster nodes" must {
      "(when two nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" in {
        node0 = ActorSystem("node0", ConfigFactory
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
        val remote0 = node0.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper0 = Gossiper(node0, remote0)

        node1 = ActorSystem("node1", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5551
              }
              cluster.node-to-join = "akka://node0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = node1.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper1 = Gossiper(node1, remote1)

        val latch = new CountDownLatch(2)

        gossiper0.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        gossiper1.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })

        latch.await(10.seconds.dilated.toMillis, TimeUnit.MILLISECONDS)

        // check cluster convergence
        gossiper0.convergence must be('defined)
        gossiper1.convergence must be('defined)
      }

      "(when three nodes) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" in {

        // ======= NODE 2 ========
        node2 = ActorSystem("node2", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty {
                hostname = localhost
                port=5552
              }
              cluster.node-to-join = "akka://node0@localhost:5550"
            }""")
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote2 = node2.provider.asInstanceOf[RemoteActorRefProvider]
        gossiper2 = Gossiper(node2, remote2)

        val latch = new CountDownLatch(3)
        gossiper0.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        gossiper1.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        gossiper2.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })

        latch.await(10.seconds.dilated.toMillis, TimeUnit.MILLISECONDS)

        // check cluster convergence
        gossiper0.convergence must be('defined)
        gossiper1.convergence must be('defined)
        gossiper2.convergence must be('defined)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    gossiper0.shutdown()
    node0.shutdown()

    gossiper1.shutdown()
    node1.shutdown()

    gossiper2.shutdown()
    node2.shutdown()
  }
}
