/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model

import java.net.{ InetSocketAddress, UnknownHostException, InetAddress }
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._

sealed abstract class RemoteAddress extends jm.RemoteAddress with ValueRenderable {
  def toOption: Option[InetAddress]
  def toIP: Option[RemoteAddress.IP]
  def isUnknown: Boolean

  /** Java API */
  def getAddress: akka.japi.Option[InetAddress] = toOption.asJava

  /** Java API */
  def getPort: Int = toIP.flatMap(_.port).getOrElse(0)
}

object RemoteAddress {
  case object Unknown extends RemoteAddress {
    def toOption = None
    def toIP = None

    def render[R <: Rendering](r: R): r.type = r ~~ "unknown"

    def isUnknown = true
  }

  final case class IP(ip: InetAddress, port: Option[Int] = None) extends RemoteAddress {
    def toOption: Option[InetAddress] = Some(ip)
    def toIP = Some(this)
    def render[R <: Rendering](r: R): r.type = {
      r ~~ ip.getHostAddress
      if (port.isDefined) r ~~ ":" ~~ port.get

      r
    }

    def isUnknown = false
  }

  def apply(s: String): RemoteAddress =
    try IP(InetAddress.getByName(s)) catch { case _: UnknownHostException ⇒ Unknown }

  def apply(a: InetAddress, port: Option[Int] = None): IP = IP(a, port)

  def apply(a: InetSocketAddress): IP = IP(a.getAddress, Some(a.getPort))

  def apply(bytes: Array[Byte]): RemoteAddress = {
    require(bytes.length == 4 || bytes.length == 16)
    try IP(InetAddress.getByAddress(bytes)) catch { case _: UnknownHostException ⇒ Unknown }
  }
}
