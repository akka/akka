/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.net.InetAddress

import akka.actor.ExtendedActorSystem
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class DnsSettingsSpec extends AkkaSpec {

  val eas = system.asInstanceOf[ExtendedActorSystem]

  "DNS settings" must {

    "use host servers if set to default" in {
      val dnsSettings = new DnsSettings(eas, ConfigFactory.parseString(
        """
          nameservers = "default"
          resolve-timeout = 1s
        """))

      dnsSettings.NameServers
    }

    "parse single name server" in {
      val dnsSettings = new DnsSettings(eas, ConfigFactory.parseString(
        """
          nameservers = "127.0.0.1"
          resolve-timeout = 1s
        """))

      dnsSettings.NameServers.map(_.getAddress) shouldEqual List(InetAddress.getByName("127.0.0.1"))
    }

    "parse list" in {
      val dnsSettings = new DnsSettings(eas, ConfigFactory.parseString(
        """
          nameservers = ["127.0.0.1", "127.0.0.2"]
          resolve-timeout = 1s
        """))

      dnsSettings.NameServers.map(_.getAddress) shouldEqual List(
        InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2")
      )
    }
  }

}
