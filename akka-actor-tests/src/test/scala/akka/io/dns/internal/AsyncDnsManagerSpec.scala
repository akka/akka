/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet6Address, InetAddress }

import scala.collection.immutable.Seq
import akka.io.{ Dns, FiniteCache }
import akka.io.dns.{ AAAARecord, ResourceRecord }
import akka.io.dns.DnsProtocol.{ Resolve, Resolved }
import akka.testkit.{ AkkaSpec, ImplicitSender }

class AsyncDnsManagerSpec extends AkkaSpec(
  """
    akka.loglevel = DEBUG
    akka.io.dns.resolver = async-dns
    akka.io.dns.async-dns.nameservers = default
  """) with ImplicitSender {

  val dns = Dns(system).manager

  "Async DNS Manager" must {
    "adapt reply back to old protocol when old protocol Dns.Resolve is received" in {
      dns ! akka.io.Dns.Resolve("127.0.0.1") // 127.0.0.1 will short circuit the resolution
      val oldProtocolReply = akka.io.Dns.Resolved("127.0.0.1", InetAddress.getByName("127.0.0.1") :: Nil)
      expectMsg(oldProtocolReply)
    }

    "support ipv6" in {
      dns ! Resolve("::1") // ::1 will short circuit the resolution
      val Resolved("::1", Seq(AAAARecord("::1", FiniteCache.effectivelyForever, _)), Nil) = expectMsgType[Resolved]
    }

    "support ipv6 also using the old protocol" in {
      dns ! akka.io.Dns.Resolve("::1") // ::1 will short circuit the resolution
      val resolved = expectMsgType[akka.io.Dns.Resolved]
      resolved.ipv4 should be(Nil)
      resolved.ipv6.length should be(1)
    }
  }

}
