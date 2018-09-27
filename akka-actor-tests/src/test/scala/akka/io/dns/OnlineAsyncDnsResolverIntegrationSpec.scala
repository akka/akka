/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.net.InetAddress

import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.{ Dns, IO }
import akka.pattern.ask
import akka.testkit.AkkaSpec
import akka.util.Timeout

import scala.concurrent.duration._

/*
Relies on being run while online
*/
class OnlineAsyncDnsResolverIntegrationSpec extends AkkaSpec(
  """
    akka.loglevel = DEBUG
    akka.io.dns.resolver = async-dns
    akka.io.dns.async-dns.nameservers = default
  """) {
  val duration = 10.seconds
  implicit val timeout = Timeout(duration)

  "Resolver" must {

    "resolve mixed A/AAAA records" in {
      val name = "akka.io"
      val answer = resolve(name)
      answer.name shouldEqual name

      answer.records.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("104.31.90.133"),
        InetAddress.getByName("104.31.91.133")
      )

      answer.records.collect { case r: AAAARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("2606:4700:30::681f:5a85"),
        InetAddress.getByName("2606:4700:30::681f:5b85")
      )
    }

    "resolve queries that are too big for UDP" in {
      val name = "many.bzzt.net"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.length should be(48)
    }

    def resolve(name: String, requestType: RequestType = Ip()): DnsProtocol.Resolved = {
      (IO(Dns) ? DnsProtocol.Resolve(name, requestType)).mapTo[DnsProtocol.Resolved].futureValue
    }

  }
}
