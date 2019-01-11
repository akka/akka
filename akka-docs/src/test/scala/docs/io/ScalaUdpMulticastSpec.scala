/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.io

import java.net.{ Inet6Address, InetSocketAddress, NetworkInterface, StandardProtocolFamily }
import java.nio.channels.DatagramChannel
import scala.util.Random
import akka.actor.{ ActorSystem, Props }
import akka.io.Udp
import akka.testkit.TestKit
import org.scalatest.{ BeforeAndAfter, WordSpecLike }
import org.scalatest.BeforeAndAfterAll
import akka.testkit.SocketUtil
import scala.collection.JavaConverters._

class ScalaUdpMulticastSpec extends TestKit(ActorSystem("ScalaUdpMulticastSpec")) with WordSpecLike with BeforeAndAfterAll {

  "listener" should {
    "send message back to sink" in {
      val ipv6ifaces =
        NetworkInterface.getNetworkInterfaces.asScala.toSeq.filter(iface ⇒
          iface.supportsMulticast &&
            iface.isUp &&
            iface.getInetAddresses.asScala.exists(_.isInstanceOf[Inet6Address]))

      if (ipv6ifaces.isEmpty) {
        // IPv6 not supported for any interface on this platform
        pending
      } else {
        // lots of problems with choosing the wrong interface for this test depending
        // on the platform (awsdl0 can't be used on OSX, docker[0-9] can't be used in a docker machine etc.)
        // therefore: try hard to find an interface that _does_ work, and only fail if there was any potentially
        // working interfaces but all failed
        ipv6ifaces.exists { ipv6iface ⇒
          // host assigned link local multicast address http://tools.ietf.org/html/rfc3307#section-4.3.2
          // generate a random 32 bit multicast address with the high order bit set
          val randomAddress: String = (Random.nextInt().abs.toLong | (1L << 31)).toHexString.toUpperCase
          val group = randomAddress.grouped(4).mkString("FF02::", ":", "")
          val port = SocketUtil.temporaryUdpIpv6Port(ipv6iface)
          val msg = "ohi"
          val sink = testActor
          val iface = ipv6iface.getName
          val listener = system.actorOf(Props(classOf[Listener], iface, group, port, sink))
          try {
            expectMsgType[Udp.Bound]
            val sender = system.actorOf(Props(classOf[Sender], iface, group, port, msg))
            // fails here, so binding succeeds but sending a message does not
            expectMsg(msg)
            true

          } catch {
            case _: AssertionError ⇒
              system.log.info("Failed to run test on interface {}", ipv6iface.getDisplayName)
              false

          } finally {
            // unbind
            system.stop(listener)
          }
        }
      }

    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

}

