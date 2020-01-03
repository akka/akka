/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.nio.ByteOrder

import akka.annotation.InternalApi
import akka.io.dns.CachePolicy.{ CachePolicy, Forever, Never, Ttl }

/**
 * INTERNAL API
 */
package object internal {

  /**
   * INTERNAL API
   *
   * We know we always want to use network byte order when writing
   */
  @InternalApi
  private[akka] implicit val networkByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  @InternalApi
  private[akka] implicit object CachePolicyIsOrdered extends Ordering[CachePolicy] {
    def compare(a: CachePolicy, b: CachePolicy): Int =
      (a, b) match {
        case (Forever, Forever) => 0
        case (Never, Never)     => 0
        case (Ttl(v1), Ttl(v2)) => v1.compare(v2)
        case (Never, _)         => -1
        case (Forever, _)       => 1
      }
  }

}
