package akka.http.model.headers

import java.net.{ UnknownHostException, InetAddress }
import akka.http.rendering._

sealed abstract class RemoteAddress extends ValueRenderable {
  def toOption: Option[InetAddress]
}

object RemoteAddress {
  case object Unknown extends RemoteAddress {
    def toOption = None
    def render[R <: Rendering](r: R): r.type = r ~~ "unknown"
  }

  case class IP(ip: InetAddress) extends RemoteAddress {
    def toOption: Option[InetAddress] = Some(ip)
    def render[R <: Rendering](r: R): r.type = r ~~ ip.getHostAddress
  }

  def apply(s: String): RemoteAddress =
    try IP(InetAddress.getByName(s)) catch { case _: UnknownHostException ⇒ Unknown }

  def apply(a: InetAddress): IP = IP(a)

  def apply(bytes: Array[Byte]): RemoteAddress = {
    require(bytes.length == 4 || bytes.length == 16)
    try IP(InetAddress.getByAddress(bytes)) catch { case _: UnknownHostException ⇒ Unknown }
  }
}
