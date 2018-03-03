/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.security.Security
import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.testkit.{ AkkaSpec, TestActorRef }

class InetAddressDnsResolverSpec extends AkkaSpec("""
    akka.io.dns.inet-address.positive-ttl = default
    akka.io.dns.inet-address.negative-ttl = default
    akka.actor.serialize-creators = on
    """) { thisSpecs ⇒

  "The DNS resolver default ttl's" must {
    "use the default value for positive caching if it is not overridden" in {
      withNewSecurityProperty("networkaddress.cache.ttl", "") {
        withNewSystemProperty("sun.net.inetaddr.ttl", "") {
          dnsResolver.positiveTtl shouldBe secondsToMillis(30)
        }
      }
    }

    "use the default value for negative caching if it is not overridden" in {
      withNewSecurityProperty("networkaddress.cache.negative.ttl", "") {
        withNewSystemProperty("sun.net.inetaddr.negative.ttl", "") {
          dnsResolver.negativeTtl shouldBe secondsToMillis(0)
        }
      }
    }

    "use the security property for positive caching if it is defined" in {
      val expectedTtlValue = "42"
      withNewSecurityProperty("networkaddress.cache.ttl", expectedTtlValue) {
        withNewSystemProperty("sun.net.inetaddr.ttl", "2000") {
          dnsResolver.positiveTtl shouldBe secondsToMillis(expectedTtlValue.toInt)
        }
      }
    }

    "use the security property for negative caching if it is defined" in {
      val expectedTtlValue = "43"
      withNewSecurityProperty("networkaddress.cache.negative.ttl", expectedTtlValue) {
        withNewSystemProperty("sun.net.inetaddr.negative.ttl", "2000") {
          dnsResolver.negativeTtl shouldBe secondsToMillis(expectedTtlValue.toInt)
        }
      }
    }

    "use the fallback system property for positive caching if it is defined and no security property is defined" in {
      val expectedTtlValue = "42"
      withNewSecurityProperty("networkaddress.cache.ttl", "") {
        withNewSystemProperty("sun.net.inetaddr.ttl", expectedTtlValue) {
          dnsResolver.positiveTtl shouldBe secondsToMillis(expectedTtlValue.toInt)
        }
      }
    }

    "use the fallback system property for negative caching if it is defined and no security property is defined" in {
      val expectedTtlValue = "43"
      withNewSecurityProperty("networkaddress.cache.negative.ttl", "") {
        withNewSystemProperty("sun.net.inetaddr.negative.ttl", expectedTtlValue) {
          dnsResolver.negativeTtl shouldBe secondsToMillis(expectedTtlValue.toInt)
        }
      }
    }
  }

  private def secondsToMillis(seconds: Int) = TimeUnit.SECONDS.toMillis(seconds)

  private def dnsResolver = {
    val actorRef = TestActorRef[InetAddressDnsResolver](Props(
      classOf[InetAddressDnsResolver],
      new SimpleDnsCache(),
      system.settings.config.getConfig("akka.io.dns.inet-address")
    ))
    actorRef.underlyingActor
  }

  private def withNewSystemProperty[T](property: String, testValue: String)(test: ⇒ T): T = {
    val oldValue = Option(System.getProperty(property))
    try {
      System.setProperty(property, testValue)
      test
    } finally {
      oldValue.foreach(v ⇒ System.setProperty(property, v))
    }
  }

  private def withNewSecurityProperty[T](property: String, testValue: String)(test: ⇒ T): T = {
    val oldValue = Option(Security.getProperty(property))
    try {
      Security.setProperty(property, testValue)
      test
    } finally {
      oldValue.foreach(v ⇒ Security.setProperty(property, v))
    }
  }

}
