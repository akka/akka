/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet6Address, InetAddress }

import akka.actor.Status.Failure
import akka.actor.{ ActorRef, ExtendedActorSystem, Props }
import akka.io.SimpleDnsCache
import akka.io.dns.CachePolicy.Ttl
import akka.io.dns.DnsProtocol._
import akka.io.dns.internal.AsyncDnsResolver.ResolveFailedException
import akka.io.dns.internal.DnsClient.{ Answer, Question4, Question6, SrvQuestion }
import akka.io.dns.{ AAAARecord, ARecord, DnsSettings, SRVRecord }
import akka.testkit.{ AkkaSpec, TestProbe, WithLogCapturing }
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }

import scala.collection.{ immutable => im }
import scala.concurrent.duration._

class AsyncDnsResolverSpec extends AkkaSpec("""
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
  """) with WithLogCapturing {

  val defaultConfig = ConfigFactory.parseString("""
          nameservers = ["one","two"]
          resolve-timeout = 300ms
          search-domains = []
          ndots = 1
          positive-ttl = forever
          negative-ttl = never
        """)

  trait Setup {
    val dnsClient1 = TestProbe()
    val dnsClient2 = TestProbe()
    val r = resolver(List(dnsClient1.ref, dnsClient2.ref), defaultConfig)
    val senderProbe = TestProbe()
    implicit val sender: ActorRef = senderProbe.ref
  }

  "Async DNS Resolver" must {
    "use dns clients in order" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq.empty))
      dnsClient2.expectNoMessage()
      senderProbe.expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "move to next client if first fails" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      // first will get ask timeout
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Failure(new RuntimeException("Nope")))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Answer(2, im.Seq.empty))
      senderProbe.expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "move to next client if first times out" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      // first will get ask timeout
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Answer(2, im.Seq.empty))
      senderProbe.expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "gets both A and AAAA records if requested" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = true))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      val ttl = Ttl.fromPositive(100.seconds)
      val ipv4Record = ARecord("cats.com", ttl, InetAddress.getByName("127.0.0.1"))
      dnsClient1.reply(Answer(1, im.Seq(ipv4Record)))
      dnsClient1.expectMsg(Question6(2, "cats.com"))
      val ipv6Record = AAAARecord("cats.com", ttl, InetAddress.getByName("::1").asInstanceOf[Inet6Address])
      dnsClient1.reply(Answer(2, im.Seq(ipv6Record)))
      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record, ipv6Record)))
    }

    "fails if all dns clients timeout" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      senderProbe.expectMsgPF(remainingOrDefault) {
        case Failure(ResolveFailedException(_)) =>
      }
    }

    "fails if all dns clients fail" in new Setup {
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Failure(new RuntimeException("Fail")))
      dnsClient2.expectMsg(Question4(2, "cats.com"))
      dnsClient2.reply(Failure(new RuntimeException("Yet another fail")))
      senderProbe.expectMsgPF(remainingOrDefault) {
        case Failure(ResolveFailedException(_)) =>
      }
    }

    "gets SRV records if requested" in new Setup {
      r ! Resolve("cats.com", Srv)
      dnsClient1.expectMsg(SrvQuestion(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq.empty))
      dnsClient2.expectNoMessage()
      senderProbe.expectMsg(Resolved("cats.com", im.Seq.empty))
    }

    "response immediately IP address" in new Setup {
      val name = "127.0.0.1"
      r ! Resolve(name)
      dnsClient1.expectNoMessage(50.millis)
      val answer = senderProbe.expectMsgType[Resolved]
      answer.records.collect { case r: ARecord => r }.toSet shouldEqual Set(
        ARecord("127.0.0.1", Ttl.effectivelyForever, InetAddress.getByName("127.0.0.1")))
    }

    "response immediately for IPv6 address" in new Setup {
      val name = "1:2:3:0:0:0:0:0"
      r ! Resolve(name)
      dnsClient1.expectNoMessage(50.millis)
      val answer = senderProbe.expectMsgType[Resolved]
      val Seq(AAAARecord("1:2:3:0:0:0:0:0", Ttl.effectivelyForever, _)) = answer.records.collect {
        case r: AAAARecord => r
      }
    }

    "return additional records for SRV requests" in new Setup {
      r ! Resolve("cats.com", Srv)
      dnsClient1.expectMsg(SrvQuestion(1, "cats.com"))
      val srvRecs = im.Seq(SRVRecord("cats.com", Ttl.fromPositive(5000.seconds), 1, 1, 1, "a.cats.com"))
      val aRecs = im.Seq(ARecord("a.cats.com", Ttl.fromPositive(1.seconds), InetAddress.getByName("127.0.0.1")))
      dnsClient1.reply(Answer(1, srvRecs, aRecs))
      dnsClient2.expectNoMessage(50.millis)
      senderProbe.expectMsg(Resolved("cats.com", srvRecs, aRecs))

      // cached the second time, don't have the probe reply
      r ! Resolve("cats.com", Srv)
      senderProbe.expectMsg(Resolved("cats.com", srvRecs, aRecs))
    }

    "don't use resolver in failure case if negative-ttl != never" in new Setup {
      val configWithSmallTtl = defaultConfig.withValue("negative-ttl", ConfigValueFactory.fromAnyRef("5s"))
      override val r = resolver(List(dnsClient1.ref), configWithSmallTtl)

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq.empty))

      senderProbe.expectMsg(Resolved("cats.com", im.Seq()))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectNoMessage(50.millis)

      senderProbe.expectMsg(Resolved("cats.com", im.Seq()))
    }

    "don't use resolver until record in cache will expired" in new Setup {
      val recordTtl = Ttl.fromPositive(100.seconds)
      val ipv4Record = ARecord("cats.com", recordTtl, InetAddress.getByName("127.0.0.1"))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq(ipv4Record)))

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectNoMessage(50.millis)

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))
    }

    "always use resolver if positive-ttl = never" in new Setup {
      val configWithSmallTtl = defaultConfig.withValue("positive-ttl", ConfigValueFactory.fromAnyRef("never"))
      override val r = resolver(List(dnsClient1.ref), configWithSmallTtl)
      val recordTtl = Ttl.fromPositive(100.seconds)

      val ipv4Record = ARecord("cats.com", recordTtl, InetAddress.getByName("127.0.0.1"))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq(ipv4Record)))

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(2, "cats.com"))
      dnsClient1.reply(Answer(2, im.Seq(ipv4Record)))

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))
    }

    "don't use resolver until cache record will be expired" in new Setup {
      val configWithSmallTtl = defaultConfig.withValue("positive-ttl", ConfigValueFactory.fromAnyRef("200 millis"))
      override val r = resolver(List(dnsClient1.ref), configWithSmallTtl)
      val recordTtl = Ttl.fromPositive(100.seconds)

      val ipv4Record = ARecord("cats.com", recordTtl, InetAddress.getByName("127.0.0.1"))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(1, "cats.com"))
      dnsClient1.reply(Answer(1, im.Seq(ipv4Record)))
      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))

      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectNoMessage(50.millis)

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))

      Thread.sleep(200)
      r ! Resolve("cats.com", Ip(ipv4 = true, ipv6 = false))
      dnsClient1.expectMsg(Question4(2, "cats.com"))
      dnsClient1.reply(Answer(2, im.Seq(ipv4Record)))

      senderProbe.expectMsg(Resolved("cats.com", im.Seq(ipv4Record)))
    }
  }

  def resolver(clients: List[ActorRef], config: Config): ActorRef = {
    val settings = new DnsSettings(system.asInstanceOf[ExtendedActorSystem], config)
    system.actorOf(Props(new AsyncDnsResolver(settings, new SimpleDnsCache(), (_, _) => {
      clients
    })))
  }
}
