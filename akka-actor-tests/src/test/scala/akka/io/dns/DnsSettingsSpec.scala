/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.net.InetAddress

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class DnsSettingsSpec extends AkkaSpec {

  val eas = system.asInstanceOf[ExtendedActorSystem]

  final val defaultConfig = ConfigFactory.parseString("""
          nameservers = "default"
          resolve-timeout = 1s
          search-domains = []
          ndots = 1
          positive-ttl = forever
          negative-ttl = never
        """)

  "DNS settings" must {

    "use host servers if set to default" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig
      )

      // Will differ based on name OS DNS servers so just validating it does not throw
      dnsSettings.NameServers
    }

    "parse a single name server" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("nameservers = \"127.0.0.1\"")))

      dnsSettings.NameServers.map(_.getAddress) shouldEqual List(InetAddress.getByName("127.0.0.1"))
    }

    "parse a list of name servers" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("nameservers = [\"127.0.0.1\", \"127.0.0.2\"]")))

      dnsSettings.NameServers.map(_.getAddress) shouldEqual List(
        InetAddress.getByName("127.0.0.1"),
        InetAddress.getByName("127.0.0.2"))
    }

    "use host search domains if set to default" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          nameservers = "127.0.0.1"
          search-domains = "default"
        """)))

      // Will differ based on name OS DNS servers so just validating it does not throw
      dnsSettings.SearchDomains shouldNot equal(List("default"))
    }

    "parse a single search domain" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          nameservers = "127.0.0.1"
          search-domains = "example.com"
        """)))

      dnsSettings.SearchDomains shouldEqual List("example.com")
    }

    "parse a single list of search domains" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          nameservers = "127.0.0.1"
          search-domains = [ "example.com", "example.net" ]
        """)))

      dnsSettings.SearchDomains shouldEqual List("example.com", "example.net")
    }

    "use host ndots if set to default" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          nameservers = "127.0.0.1"
          search-domains = "example.com"
          ndots = "default"
        """)))


      // Will differ based on name OS DNS servers so just validating it does not throw
      dnsSettings.NDots
    }

    "parse ndots" in {
      val dnsSettings = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          nameservers = "127.0.0.1"
          search-domains = "example.com"
          ndots = 5
        """)))

      dnsSettings.NDots shouldEqual 5
    }

    "parse ttl" in {
      val dnsSettingsNeverForever = new DnsSettings(
        eas,
        defaultConfig)

      dnsSettingsNeverForever.positiveCachePolicy shouldEqual CachePolicy.Forever
      dnsSettingsNeverForever.negativeCachePolicy shouldEqual CachePolicy.Never

      val dnsSettingsDuration = new DnsSettings(
        eas,
        defaultConfig.withFallback(ConfigFactory.parseString("""
          positive-ttl = 10 s
          negative-ttl = 10 d
        """)))

      dnsSettingsDuration.positiveCachePolicy shouldEqual CachePolicy.Ttl.fromPositive(10.seconds)
      dnsSettingsDuration.negativeCachePolicy shouldEqual CachePolicy.Ttl.fromPositive(10.days)
    }
  }

}
