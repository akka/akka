/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.annotation.InternalApi

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.duration._

object CachePolicy {

  /**
   * INTERNAL API
   */
  @InternalApi
  sealed trait CachePolicy
  /**
   * INTERNAL API
   */
  @InternalApi
  case object Never extends CachePolicy
  /**
   * INTERNAL API
   */
  @InternalApi
  case object Forever extends CachePolicy
  /**
   * INTERNAL API
   */
  @InternalApi
  case class Ttl(value: FiniteDuration) extends CachePolicy {
    require(value > Duration.Zero)
    import akka.util.JavaDurationConverters._
    def getValue: java.time.Duration = value.asJava
  }
  /**
   * INTERNAL API
   */
  @InternalApi
  object Ttl {
    // There's places where only a Ttl makes sense (DNS RFC says TTL is a positive 32 but integer)
    // but we know the value can be cached effectively forever (e.g. the Lookup name was the actual IP already)
    val effectivelyForever: Ttl = Ttl(Int.MaxValue.seconds)

    implicit object TtlIsOrdered extends Ordering[Ttl] {
      def compare(a: Ttl, b: Ttl) = a.value.compare(b.value)
    }
  }

}
