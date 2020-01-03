/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

import akka.remote.RemotingMultiNodeSpec
import akka.remote.testkit.MultiNodeConfig
import com.github.ghik.silencer.silent

object DnsSpec extends MultiNodeConfig {
  val first = role("first")
}

class DnsSpecMultiJvmNode1 extends DnsSpec

// This is a multi-jvm tests because it is modifying global System.properties
@silent("deprecated")
class DnsSpec extends RemotingMultiNodeSpec(DnsSpec) {

  def initialParticipants = roles.size

  val ip4Address = InetAddress.getByAddress("localhost", Array[Byte](127, 0, 0, 1)) match {
    case address: Inet4Address => address
  }
  val ipv6Address =
    InetAddress.getByAddress("localhost", Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)) match {
      case address: Inet6Address => address
    }

  var temporaryValue: Option[String] = None

  override def atStartup(): Unit = {
    temporaryValue = sys.props.get("java.net.preferIPv6Addresses")
  }

  override def afterTermination(): Unit = {
    temporaryValue match {
      case Some(value) => sys.props.put("java.net.preferIPv6Addresses", value)
      case _           => sys.props.remove("java.net.preferIPv6Addresses")
    }
  }

  "Dns" must {

    "resolve to a IPv6 address if it is the preferred network stack" in {
      sys.props.put("java.net.preferIPv6Addresses", true.toString)
      Dns.Resolved("test", List(ip4Address), List(ipv6Address)).addr should ===(ipv6Address)
    }
    "resolve to a IPv4 address if IPv6 is not the preferred network stack" in {
      sys.props.remove("java.net.preferIPv6Addresses")
      Dns.Resolved("test", List(ip4Address), List(ipv6Address)).addr should ===(ip4Address)
    }

  }

}
