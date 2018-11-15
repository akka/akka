package akka.discovery.dns

import akka.actor.ActorSystem
import akka.discovery.ServiceDiscovery.ResolvedTarget
import akka.discovery.{Discovery, Lookup }
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

/*
Testing is done via subbing out the DnsClient. To test against a real dns server
install bind and add this to /etc/named.conf

zone "akka.test" IN {
  type master;
  file "akka.test.zone";
};

zone "akka.test2" IN {
  type master;
  file "akka.test2.zone";
};

Then add the two zone files to /var/named/ akka.test.zone and akka.test2.zone

akka.test.zone:
=================

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

_service._tcp   86400 IN    SRV 10       60     5060 a-single
_service._tcp   86400 IN    SRV 10       40     5070 a-double

cname-in IN CNAME  a-double
cname-ext IN CNAME  a-single.akka.test2.

akka.test.zone:
=================

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

object DnsDiscoverySpec {

  val config = ConfigFactory.parseString("""
     //#configure-dns
     akka {
       discovery.method = akka-dns
       io.dns.resolver = async-dns
     }
     //#configure-dns
     akka {
       loglevel = DEBUG
       io.dns.async-dns.nameservers = ["localhost"]
     }
    """)

}

class DnsDiscoverySpec
  extends TestKit(ActorSystem("DnsDiscoverySpec", DnsDiscoverySpec.config))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures {

  "Dns Discovery" must {

    // Requires DNS server, see above, FIXME use the dns testing infra from dns
    pending

    "work with SRV records" in {
      val discovery = Discovery(system).discovery
      val name = "_service._tcp.akka.test."
      val result =
        discovery
          .lookup(Lookup("akka.test.").withPortName("service").withProtocol("tcp"), resolveTimeout = 500.milliseconds)
          .futureValue
      result.addresses.toSet shouldEqual Set(
        ResolvedTarget("a-single.akka.test", Some(5060)),
        ResolvedTarget("a-double.akka.test", Some(5070))
      )
      result.serviceName shouldEqual name
    }

    "work with IP records" in {
      val discovery = Discovery(system).discovery
      val name = "a-single.akka.test"
      val result = discovery.lookup(name, resolveTimeout = 500.milliseconds).futureValue
      result.serviceName shouldEqual name
      result.addresses.toSet shouldEqual Set(
        ResolvedTarget("192.168.1.20", None)
      )
    }
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}