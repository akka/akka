/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet6Address, InetAddress }

import akka.actor.Status.Failure
import akka.actor.{ ActorRef, ExtendedActorSystem, Props }
import akka.io.dns.DnsProtocol._
import akka.io.dns.internal.AsyncDnsResolver.ResolveFailedException
import akka.io.dns.CachePolicy.Ttl
import akka.io.dns.internal.DnsClient.{ Answer, Question4, Question6, SrvQuestion }
import akka.io.dns.{ AAAARecord, ARecord, DnsSettings, SRVRecord }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import com.typesafe.config.ConfigFactory
import akka.testkit.WithLogCapturing

import scala.concurrent.duration._
import scala.collection.{ immutable ⇒ im }
import scala.concurrent.duration._

class AsyncDnsResolverSpec extends AkkaSpec(
  """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
  """) with ImplicitSender with WithLogCapturing {

  "Async DNS Resolver" must {

    "use dns clients in order" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq.empty))
      dnsClient2.expectNoMessage()
      expectMsg(Resolved("cats.com", im.Seq.empty))
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
      dnsClient2.reply(Answer(2, im.Seq.empty))
      expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "move to next client if first times out" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      // first will get ask timeout
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Answer(2, im.Seq.empty))
      expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "gets both A and AAAA records if requested" in {
      val dnsClient1 = TestProbe()
      val r = resolver(List(dnsClient1.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = true))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      val ttl = Ttl.fromPositive(100.seconds)
      val ipv4Record = ARecord("cats.com", ttl, InetAddress.getByName("127.0.0.1"))
      dnsClient1.reply(Answer(1, im.Seq(ipv4Record)))
      dnsClient1.expectMsg(Question6(2, "cats.com"))
      val ipv6Record = AAAARecord("cats.com", ttl, InetAddress.getByName("::1").asInstanceOf[Inet6Address])
      dnsClient1.reply(Answer(2, im.Seq(ipv6Record)))
      expectMsg(Resolved("cats.com", im.Seq(ipv4Record, ipv6Record)))
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

    "fails if all dns clients fail" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Failure(new RuntimeException("Fail")))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Failure(new RuntimeException("Yet another fail")))
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
      dnsClient1.reply(Answer(1, im.Seq.empty))
      dnsClient2.expectNoMessage()
      expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "response immediately IP address" in {
      val name = "127.0.0.1"
      val dnsClient1 = TestProbe()
      val r = resolver(List(dnsClient1.ref))
      r ! Resolve(name)
      dnsClient1.expectNoMessage(50.millis)
      val answer = expectMsgType[Resolved]
      answer.records.collect { case r: ARecord ⇒ r }.toSet shouldEqual Set(
        ARecord("127.0.0.1", Ttl.effectivelyForever, InetAddress.getByName("127.0.0.1"))
      )
    }

    "response immediately for IPv6 address" in {
      val name = "1:2:3:0:0:0:0:0"
      val dnsClient1 = TestProbe()
      val r = resolver(List(dnsClient1.ref))
      r ! Resolve(name)
      dnsClient1.expectNoMessage(50.millis)
      val answer = expectMsgType[Resolved]
      val Seq(AAAARecord("1:2:3:0:0:0:0:0", Ttl.effectivelyForever, _)) = answer.records.collect { case r: AAAARecord ⇒ r }
    }

    "return additional records for SRV requests" in {
      val dnsClient1 = TestProbe()
      val dnsClient2 = TestProbe()
      val r = resolver(List(dnsClient1.ref, dnsClient2.ref))
      r ! Resolve("cats.com", Srv)
      dnsClient1.expectMsg(SrvQuestion(1, "cats.com"))
      val srvRecs = im.Seq(SRVRecord("cats.com", Ttl.fromPositive(5000.seconds), 1, 1, 1, "a.cats.com"))
      val aRecs = im.Seq(ARecord("a.cats.com", Ttl.fromPositive(1.seconds), InetAddress.getByName("127.0.0.1")))
      dnsClient1.reply(Answer(1, srvRecs, aRecs))
      dnsClient2.expectNoMessage(50.millis)
      expectMsg(Resolved("cats.com", srvRecs, aRecs))

      // cached the second time, don't have the probe reply
      r ! Resolve("cats.com", Srv)
      expectMsg(Resolved("cats.com", srvRecs, aRecs))
    }
  }

  def resolver(clients: List[ActorRef]): ActorRef = {
    val settings = new DnsSettings(system.asInstanceOf[ExtendedActorSystem], ConfigFactory.parseString(
      """
          nameservers = ["one","two"]
          resolve-timeout = 25ms
          search-domains = []
          ndots = 1
        """))
    system.actorOf(Props(new AsyncDnsResolver(settings, new AsyncDnsCache(), (arf, l) ⇒ {
      clients
    })))
  }
}
