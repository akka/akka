package akka.io

import java.net.{ Inet4Address, Inet6Address, InetAddress }

import scala.collection.immutable.Seq
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

class DnsSpec extends WordSpec with BeforeAndAfterAll with Matchers {

  val ip4Address = InetAddress.getByAddress("localhost", Array[Byte](127, 0, 0, 1)) match {
    case address: Inet4Address ⇒ address
  }
  val ipv6Address = InetAddress.getByAddress("localhost", Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)) match {
    case address: Inet6Address ⇒ address
  }

  var temporaryValue: Option[String] = None

  override def beforeAll() = {
    temporaryValue = sys.props.get("java.net.preferIPv6Addresses")
  }

  override def afterAll() = temporaryValue match {
    case Some(value) ⇒ sys.props.put("java.net.preferIPv6Addresses", value)
    case _           ⇒ sys.props.remove("java.net.preferIPv6Addresses")
  }

  "Dns" should {
    "resolve to a IPv6 address if it is the preferred network stack" in {
      sys.props.put("java.net.preferIPv6Addresses", true.toString)
      Dns.Resolved("test", Seq(ip4Address), Seq(ipv6Address)).addr should ===(ipv6Address)
    }
    "resolve to a IPv4 address if IPv6 is not the preferred network stack" in {
      sys.props.remove("java.net.preferIPv6Addresses")
      Dns.Resolved("test", Seq(ip4Address), Seq(ipv6Address)).addr should ===(ip4Address)
    }
  }
}
