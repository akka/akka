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
Relies on two zones setup, akka.test and akka.test2 e.g.
* Install bind
* Create the two zones in /var/named/akka.test.zone and /var/named/akka.test2.zone
* Add the following to /etc/named.conf:

zone "akka.test" IN {
  type master;
  file "akka.test.zone";
};

zone "akka.test2" IN {
  type master;
  file "akka.test2.zone";
};


/var/named/akka.test.zone:

$TTL 86400

@ IN SOA akka.test root.akka.test (
  2017010302
  3600
  900
  604800
  86400
)

@      IN NS test
test     IN A  192.168.1.19
a-single IN A  192.168.1.20
a-double IN A  192.168.1.21
a-double IN A  192.168.1.22
aaaa-single IN AAAA fd4d:36b2:3eca:a2d8:0:0:0:1
aaaa-double IN AAAA fd4d:36b2:3eca:a2d8:0:0:0:2
aaaa-double IN AAAA fd4d:36b2:3eca:a2d8:0:0:0:3
a-aaaa IN AAAA fd4d:36b2:3eca:a2d8:0:0:0:4
a-aaaa IN AAAA fd4d:36b2:3eca:a2d8:0:0:0:5
a-aaaa IN A  192.168.1.23
a-aaaa IN A  192.168.1.24

service.tcp   86400 IN    SRV 10       60     5060 a-single
service.tcp   86400 IN    SRV 10       40     5070 a-double

cname-in IN CNAME  a-double
cname-ext IN CNAME  a-single.akka.test2.

/var/named/akka.test2.zone:

$TTL 86400

@ IN SOA akka.test2 root.akka.test2 (
  2017010302
  3600
  900
  604800
  86400
)

@      IN NS test2
test2     IN A  192.168.2.19
a-single IN A  192.168.2.20

*/
class AsyncDnsResolverIntegrationSpec extends AkkaSpec(
  """
    akka.loglevel = DEBUG
    akka.io.dns.resolver = async-dns
    akka.io.dns.async-dns.nameservers = [localhost]
//    akka.io.dns.async-dns.nameservers = default
  """) {
  val duration = 10.seconds
  implicit val timeout = Timeout(duration)

  "Resolver" must {

    pending // PENDING since needs `bind` server to be running to test end-to-end

    "resolve single A record" in {
      val name = "a-single.akka.test"
      val answer = resolve(name, DnsProtocol.Ip(ipv6 = false))
      withClue(answer) {
        answer.name shouldEqual name
        answer.results.size shouldEqual 1
        answer.results.head.name shouldEqual name
        answer.results.head.asInstanceOf[ARecord].ip shouldEqual InetAddress.getByName("192.168.1.20")
      }
    }

    "resolve double A records" in {
      val name = "a-double.akka.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.map(_.asInstanceOf[ARecord].ip).toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.21"),
        InetAddress.getByName("192.168.1.22")
      )
    }

    "resolve single AAAA record" in {
      val name = "aaaa-single.akka.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.map(_.asInstanceOf[AAAARecord].ip) shouldEqual Seq(InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:1"))
    }

    "resolve double AAAA records" in {
      val name = "aaaa-double.akka.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.map(_.asInstanceOf[AAAARecord].ip).toSet shouldEqual Set(
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:2"),
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:3")
      )
    }

    "resolve mixed A/AAAA records" in {
      val name = "a-aaaa.akka.test"
      val answer = resolve(name)
      answer.name shouldEqual name

      answer.results.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.23"),
        InetAddress.getByName("192.168.1.24")
      )

      answer.results.collect { case r: AAAARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:4"),
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:5")
      )
    }

    "resolve external CNAME record" in {
      val name = "cname-ext.akka.test"
      val answer = (IO(Dns) ? DnsProtocol.Resolve(name)).mapTo[DnsProtocol.Resolved].futureValue
      answer.name shouldEqual name
      answer.results.collect { case r: CNameRecord ⇒ r.canonicalName }.toSet shouldEqual Set(
        "a-single.akka.test2"
      )
      answer.results.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.2.20")
      )
    }

    "resolve internal CNAME record" in {
      val name = "cname-in.akka.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.collect { case r: CNameRecord ⇒ r.canonicalName }.toSet shouldEqual Set(
        "a-double.akka.test"
      )
      answer.results.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.21"),
        InetAddress.getByName("192.168.1.22")
      )
    }

    "resolve SRV record" in {
      val name = "service.tcp.akka.test"
      val answer = resolve("service.tcp.akka.test", Srv)

      answer.name shouldEqual name
      answer.results.collect { case r: SRVRecord ⇒ r }.toSet shouldEqual Set(
        SRVRecord("service.tcp.akka.test", 86400, 10, 60, 5060, "a-single.akka.test"),
        SRVRecord("service.tcp.akka.test", 86400, 10, 40, 5070, "a-double.akka.test")
      )
    }

    "not hang when resolving raw IP address" in {
      val name = "127.0.0.1"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.collect { case r: ARecord ⇒ r }.toSet shouldEqual Set(
        ARecord("127.0.0.1", Int.MaxValue, InetAddress.getByName("127.0.0.1"))
      )
    }
    "not hang when resolving raw IPv6 address" in {
      val name = "1:2:3:0:0:0:0:0"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.results.collect { case r: ARecord ⇒ r }.toSet shouldEqual Set(
        ARecord("1:2:3:0:0:0:0:0", Int.MaxValue, InetAddress.getByName("1:2:3:0:0:0:0:0"))
      )
    }

    "resolve same address twice" in {
      resolve("a-single.akka.test").results.map(_.asInstanceOf[ARecord].ip) shouldEqual Seq(InetAddress.getByName("192.168.1.20"))
      resolve("a-single.akka.test").results.map(_.asInstanceOf[ARecord].ip) shouldEqual Seq(InetAddress.getByName("192.168.1.20"))
    }

    "handle nonexistent domains" in {
      val answer = (IO(Dns) ? DnsProtocol.Resolve("nonexistent.akka.test")).mapTo[DnsProtocol.Resolved].futureValue
      answer.results shouldEqual List.empty
    }

    def resolve(name: String, requestType: RequestType = Ip()) = {
      (IO(Dns) ? DnsProtocol.Resolve(name, requestType)).mapTo[DnsProtocol.Resolved].futureValue
    }

  }
}
