/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import java.net.InetSocketAddress

object RemoteAddress {
  def apply(hostname: String, port: Int) = new RemoteAddress(hostname, port)
  def apply(inetAddress: InetSocketAddress): RemoteAddress = inetAddress match {
    case null ⇒ null
    case inet ⇒ new RemoteAddress(inet.getAddress.getHostAddress, inet.getPort)
  }
}

class RemoteAddress(val hostname: String, val port: Int) {
  override val hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, hostname)
    result = HashCode.hash(result, port)
    result
  }

  override def equals(that: Any): Boolean = {
    that.isInstanceOf[RemoteAddress] &&
      that.asInstanceOf[RemoteAddress].hostname == hostname &&
      that.asInstanceOf[RemoteAddress].port == port
  }
}
