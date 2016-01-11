/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.net.{ InetAddress, InetSocketAddress }

import org.scalatest.{ Matchers, WordSpec }

class EnhancedInetSocketAddressSpec extends WordSpec with Matchers {
  "getHostStringJava6Compatible" should {
    "return IPv4 address if InetSocketAddress was created with the address" in {
      val addr = likelyReverseResolvableAddress
      val socketAddress = new InetSocketAddress(addr, 80)
      socketAddress.getHostStringJava6Compatible shouldEqual addr.getHostAddress
    }
    "return host name if InetSocketAddress was created with host name" in {
      val address = new InetSocketAddress("github.com", 80)
      address.getHostStringJava6Compatible shouldEqual "github.com"
    }
  }

  /**
   * Returns an InetAddress that can likely be reverse looked up, so that
   * getHostName returns a DNS address and not the IP. Unfortunately, we
   * cannot be sure that a host name was already cached somewhere in which
   * case getHostString may still return a host name even without doing
   * a reverse lookup at this time. If this start to fail non-deterministically,
   * it may be decided that this test needs to be disabled.
   */
  def likelyReverseResolvableAddress: InetAddress =
    InetAddress.getByAddress(InetAddress.getByName("google.com").getAddress)
}
