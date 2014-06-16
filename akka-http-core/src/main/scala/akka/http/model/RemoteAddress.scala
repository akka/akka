/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import java.net.{ UnknownHostException, InetAddress }
import akka.http.util._

import japi.JavaMapping.Implicits._

sealed abstract class RemoteAddress extends japi.RemoteAddress with ValueRenderable {
  def toOption: Option[InetAddress]
  def isUnknown: Boolean

  /** Java API */
  def getAddress: akka.japi.Option[InetAddress] = toOption.asJava
}

object RemoteAddress {
  case object Unknown extends RemoteAddress {
    def toOption = None
    def render[R <: Rendering](r: R): r.type = r ~~ "unknown"

    def isUnknown = true
  }

  final case class IP(ip: InetAddress) extends RemoteAddress {
    def toOption: Option[InetAddress] = Some(ip)
    def render[R <: Rendering](r: R): r.type = r ~~ ip.getHostAddress

    def isUnknown = false
  }

  def apply(s: String): RemoteAddress =
    try IP(InetAddress.getByName(s)) catch { case _: UnknownHostException ⇒ Unknown }

  def apply(a: InetAddress): IP = IP(a)

  def apply(bytes: Array[Byte]): RemoteAddress = {
    require(bytes.length == 4 || bytes.length == 16)
    try IP(InetAddress.getByAddress(bytes)) catch { case _: UnknownHostException ⇒ Unknown }
  }
}
