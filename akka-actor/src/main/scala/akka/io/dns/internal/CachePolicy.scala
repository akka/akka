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
  sealed abstract case class Ttl(value: FiniteDuration) extends CachePolicy {
    import akka.util.JavaDurationConverters._
    def getValue: java.time.Duration = value.asJava
  }
  /**
   * INTERNAL API
   */
  @InternalApi
  object Ttl {
    def fromPositive(value: FiniteDuration): Ttl = {
      if (value <= Duration.Zero) throw new IllegalArgumentException(s"TTL values must be a positive.")
      new Ttl(value) {}
    }

    // There's places where only a Ttl makes sense (DNS RFC says TTL is a positive 32 but integer)
    // but we know the value can be cached effectively forever (e.g. the Lookup name was the actual IP already)
    val effectivelyForever: Ttl = fromPositive(Int.MaxValue.seconds)

    implicit object TtlIsOrdered extends Ordering[Ttl] {
      def compare(a: Ttl, b: Ttl) = a.value.compare(b.value)
    }
  }

}
