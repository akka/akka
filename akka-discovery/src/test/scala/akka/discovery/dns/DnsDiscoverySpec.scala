/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery.dns

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.discovery.{ Discovery, Lookup }
import akka.discovery.ServiceDiscovery.ResolvedTarget
import akka.io.dns.DockerBindDnsService
import akka.testkit.{ AkkaSpec, SocketUtil, TestKit }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object DnsDiscoverySpec {

  val config = ConfigFactory.parseString(
    s"""
     //#configure-dns
     akka {
       discovery {
        method = akka-dns
       }
     }
     //#configure-dns
     akka {
       loglevel = DEBUG
     }
      akka.io.dns.async-dns.nameservers = ["localhost:${DnsDiscoverySpec.dockerDnsServerPort}"]
    """)

  lazy val dockerDnsServerPort = SocketUtil.temporaryLocalPort()

  val configWithAsyncDnsResolverAsDefault = ConfigFactory.parseString(
    """
      akka.io.dns.resolver = "async-dns"
    """).withFallback(config)

}

class DnsDiscoverySpec extends AkkaSpec(DnsDiscoverySpec.config)
  with DockerBindDnsService {

  import DnsDiscoverySpec._

  override val hostPort: Int = DnsDiscoverySpec.dockerDnsServerPort

  val systemWithAsyncDnsAsResolver = ActorSystem("AsyncDnsSystem", configWithAsyncDnsResolverAsDefault)

  "Dns Discovery with isolated resolver" must {

    if (!dockerAvailable()) {
      info("Test not run as docker is not available")
      pending
    }

    "work with SRV records" in {
      val discovery = Discovery(system).discovery
      val name = "_service._tcp.foo.test."
      val result =
        discovery
          .lookup(Lookup("foo.test.").withPortName("service").withProtocol("tcp"), resolveTimeout = 10.seconds)
          .futureValue
      result.addresses.toSet shouldEqual Set(
        ResolvedTarget("a-single.foo.test", Some(5060), Some(InetAddress.getByName("192.168.1.20"))),
        ResolvedTarget("a-double.foo.test", Some(65535), Some(InetAddress.getByName("192.168.1.21"))),
        ResolvedTarget("a-double.foo.test", Some(65535), Some(InetAddress.getByName("192.168.1.22")))
      )
      result.serviceName shouldEqual name
    }

    "work with IP records" in {
      val discovery = Discovery(system).discovery
      val name = "a-single.foo.test"
      val result = discovery.lookup(name, resolveTimeout = 500.milliseconds).futureValue
      result.serviceName shouldEqual name
      result.addresses.toSet shouldEqual Set(
        ResolvedTarget("192.168.1.20", None)
      )
    }

    "be using its own resolver" in {
      // future will fail if it it doesn't exist
      system.actorSelection("/system/SD-DNS/async-dns").resolveOne(2.seconds).futureValue
    }

  }

  "Dns discovery with the system resolver" must {
    if (!dockerAvailable()) {
      info("Test not run as docker is not available")
      pending
    }

    "work with SRV records" in {
      val discovery = Discovery(systemWithAsyncDnsAsResolver).discovery
      val name = "_service._tcp.foo.test."
      val result =
        discovery
          .lookup(Lookup("foo.test.").withPortName("service").withProtocol("tcp"), resolveTimeout = 10.seconds)
          .futureValue
      result.addresses.toSet shouldEqual Set(
        ResolvedTarget("a-single.foo.test", Some(5060), Some(InetAddress.getByName("192.168.1.20"))),
        ResolvedTarget("a-double.foo.test", Some(65535), Some(InetAddress.getByName("192.168.1.21"))),
        ResolvedTarget("a-double.foo.test", Some(65535), Some(InetAddress.getByName("192.168.1.22")))
      )
      result.serviceName shouldEqual name
    }

    "be using the system resolver" in {
      // check the service discovery one doesn't exist
      systemWithAsyncDnsAsResolver.actorSelection("/system/SD-DNS/async-dns").resolveOne(2.seconds).failed.futureValue
    }

  }

  override def afterTermination(): Unit = {
    super.afterTermination()
    TestKit.shutdownActorSystem(system)
  }
}
