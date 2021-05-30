/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetAddress

import akka.io.Dns
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
  }

}
