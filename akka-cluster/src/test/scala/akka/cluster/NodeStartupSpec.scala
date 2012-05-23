/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import java.net.InetSocketAddress

import akka.testkit._
import akka.dispatch._
import akka.actor._
import akka.remote._
import akka.util.duration._

import com.typesafe.config._

class NodeStartupSpec extends ClusterSpec with ImplicitSender {
  val portPrefix = 8

  var node0: Cluster = _
  var node1: Cluster = _
  var system0: ActorSystemImpl = _
  var system1: ActorSystemImpl = _

  try {
    "A first cluster node with a 'node-to-join' config set to empty string (singleton cluster)" must {
      system0 = ActorSystem("system0", ConfigFactory
        .parseString("""
          akka {
            actor.provider = "akka.remote.RemoteActorRefProvider"
            remote.netty.port=%d550
          }""".format(portPrefix))
        .withFallback(system.settings.config))
        .asInstanceOf[ActorSystemImpl]
      val remote0 = system0.provider.asInstanceOf[RemoteActorRefProvider]
      node0 = Cluster(system0)

      "be a singleton cluster when started up" taggedAs LongRunningTest in {
        Thread.sleep(1.seconds.dilated.toMillis)
        node0.isSingletonCluster must be(true)
      }

      "be in 'Joining' phase when started up" taggedAs LongRunningTest in {
        val members = node0.latestGossip.members
        val joiningMember = members find (_.address.port.get == 550.withPortPrefix)
        joiningMember must be('defined)
        joiningMember.get.status must be(MemberStatus.Joining)
      }
    }

    "A second cluster node with a 'node-to-join' config defined" must {
      "join the other node cluster when sending a Join command" taggedAs LongRunningTest in {
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

        Thread.sleep(10.seconds.dilated.toMillis) // give enough time for node1 to JOIN node0 and leader to move him to UP
        val members = node0.latestGossip.members
        val joiningMember = members find (_.address.port.get == 551.withPortPrefix)
        joiningMember must be('defined)
        joiningMember.get.status must be(MemberStatus.Up)
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
  }
}
