/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet6Address, InetAddress }

import akka.actor.Status.Failure
import akka.actor.{ ActorRef, ExtendedActorSystem, Props }
import akka.io.dns.{ AAAARecord, ARecord, DnsSettings }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import com.typesafe.config.ConfigFactory
import akka.io.dns.DnsProtocol._
import akka.io.dns.internal.AsyncDnsResolver.ResolveFailedException
import akka.io.dns.internal.DnsClient.{ Answer, Question4, Question6, SrvQuestion }

import scala.collection.immutable

class AsyncDnsResolverSpec extends AkkaSpec(
  """
    akka.loglevel = INFO
  """) with ImplicitSender {

  "Async DNS Resolver" must {

    "use dns clients in order" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, immutable.Seq()))
      dnsClient2.expectNoMessage()
      expectMsg(Resolved("cats.com", immutable.Seq()))
    }

    "move to next client if first fails" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      // first will get ask timeout
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Failure(new RuntimeException("Nope")))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Answer(2, immutable.Seq()))
      expectMsg(Resolved("cats.com", immutable.Seq()))
    }

    "move to next client if first times out" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      // first will get ask timeout
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Answer(2, immutable.Seq()))
      expectMsg(Resolved("cats.com", immutable.Seq()))
    }

    "gets both A and AAAA records if requested" in {
      val dnsClient1 = TestProbe()
      val r = resolver(List(dnsClient1.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = true))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      val ipv4Record = ARecord("cats.com", 100, InetAddress.getByName("127.0.0.1"))
      dnsClient1.reply(Answer(1, immutable.Seq(ipv4Record)))
      dnsClient1.expectMsg(Question6(2, "cats.com"))
      val ipv6Record = AAAARecord("cats.com", 100, InetAddress.getByName("::1").asInstanceOf[Inet6Address])
      dnsClient1.reply(Answer(2, immutable.Seq(ipv6Record)))
      expectMsg(Resolved("cats.com", immutable.Seq(ipv4Record, ipv6Record)))
    }

    "fails if all dns clients timeout" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      expectMsgPF(remainingOrDefault) {
        case Failure(ResolveFailedException(_)) ⇒
      }
    }

    "gets SRV records if requested" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Srv)
      dnsClient1.expectMsg(SrvQuestion(1, "cats.com"))
      dnsClient1.reply(Answer(1, immutable.Seq()))
      dnsClient2.expectNoMessage()
      expectMsg(Resolved("cats.com", immutable.Seq()))
    }
  }

  def resolver(clients: List[ActorRef]): ActorRef = {
    val settings = new DnsSettings(system.asInstanceOf[ExtendedActorSystem], ConfigFactory.parseString(
      """
          nameservers = ["one","two"]
          resolve-timeout = 25ms
        """))
    system.actorOf(Props(new AsyncDnsResolver(settings, new AsyncDnsCache(), (arf, l) ⇒ {
      clients
    })))
  }
}
