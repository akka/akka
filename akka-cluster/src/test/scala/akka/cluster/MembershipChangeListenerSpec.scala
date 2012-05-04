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

class MembershipChangeListenerSpec extends ClusterSpec with ImplicitSender {
  val portPrefix = 6

  var node0: Cluster = _
  var node1: Cluster = _
  var node2: Cluster = _

  var system0: ActorSystemImpl = _
  var system1: ActorSystemImpl = _
  var system2: ActorSystemImpl = _

  try {
    "A set of connected cluster systems" must {
      "(when two systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {
        system0 = ActorSystem("system0", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port = %d550
            }""".format(portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
        node0 = Cluster(system0)

        system1 = ActorSystem("system1", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port=%d551
              cluster.node-to-join = "akka://system0@localhost:%d550"
            }""".format(portPrefix, portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote1 = system1.provider.asInstanceOf[RemoteActorRefProvider]
        node1 = Cluster(system1)

        val latch = new CountDownLatch(2)

        node0.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        node1.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })

        latch.await(10.seconds.dilated.toMillis, TimeUnit.MILLISECONDS)

        Thread.sleep(10.seconds.dilated.toMillis)

        // check cluster convergence
        node0.convergence must be('defined)
        node1.convergence must be('defined)
      }

      "(when three systems) after cluster convergence updates the membership table then all MembershipChangeListeners should be triggered" taggedAs LongRunningTest in {

        // ======= NODE 2 ========
        system2 = ActorSystem("system2", ConfigFactory
          .parseString("""
            akka {
              actor.provider = "akka.remote.RemoteActorRefProvider"
              remote.netty.port=%d552
              cluster.node-to-join = "akka://system0@localhost:%d550"
            }""".format(portPrefix, portPrefix))
          .withFallback(system.settings.config))
          .asInstanceOf[ActorSystemImpl]
        val remote2 = system2.provider.asInstanceOf[RemoteActorRefProvider]
        node2 = Cluster(system2)

        val latch = new CountDownLatch(3)
        node0.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        node1.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })
        node2.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            latch.countDown()
          }
        })

        latch.await(30.seconds.dilated.toMillis, TimeUnit.MILLISECONDS)
      }
    }
  } catch {
    case e: Exception ⇒
      e.printStackTrace
      fail(e.toString)
  }

  override def atTermination() {
    if (node0 ne null) node0.shutdown()
    if (system0 ne null) system0.shutdown()

    if (node1 ne null) node1.shutdown()
    if (system1 ne null) system1.shutdown()

    if (node2 ne null) node2.shutdown()
    if (system2 ne null) system2.shutdown()
  }
}
