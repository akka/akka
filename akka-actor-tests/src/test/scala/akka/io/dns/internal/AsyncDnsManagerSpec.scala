/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetAddress

import akka.io.Dns
import akka.io.dns.AAAARecord
import akka.io.dns.DnsProtocol.{ Resolve, Resolved }
import akka.io.dns.CachePolicy.Ttl
import akka.testkit.WithLogCapturing
import akka.testkit.{ AkkaSpec, ImplicitSender }

import scala.collection.immutable.Seq

class AsyncDnsManagerSpec extends AkkaSpec(
  """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.io.dns.resolver = async-dns
    akka.io.dns.async-dns.nameservers = default
  """) with ImplicitSender with WithLogCapturing {

  val dns = Dns(system).manager

  "Async DNS Manager" must {
    "adapt reply back to old protocol when old protocol Dns.Resolve is received" in {
      dns ! akka.io.Dns.Resolve("127.0.0.1") // 127.0.0.1 will short circuit the resolution
      val oldProtocolReply = akka.io.Dns.Resolved("127.0.0.1", InetAddress.getByName("127.0.0.1") :: Nil)
      expectMsg(oldProtocolReply)
    }

    "support ipv6" in {
      dns ! Resolve("::1") // ::1 will short circuit the resolution
      val Resolved("::1", Seq(AAAARecord("::1", Ttl.effectivelyForever, _)), Nil) = expectMsgType[Resolved]
    }

    "support ipv6 also using the old protocol" in {
      dns ! akka.io.Dns.Resolve("::1") // ::1 will short circuit the resolution
      val resolved = expectMsgType[akka.io.Dns.Resolved]
      resolved.ipv4 should be(Nil)
      resolved.ipv6.length should be(1)
    }
  }

}
