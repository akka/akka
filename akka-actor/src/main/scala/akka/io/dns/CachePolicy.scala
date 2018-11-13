/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import akka.annotation.ApiMayChange

import scala.concurrent.duration.{ Duration, FiniteDuration, _ }

object CachePolicy {

  @ApiMayChange
  sealed trait CachePolicy
  @ApiMayChange
  case object Never extends CachePolicy
  @ApiMayChange
  case object Forever extends CachePolicy
  @ApiMayChange
  sealed abstract case class Ttl(value: FiniteDuration) extends CachePolicy {
    import akka.util.JavaDurationConverters._
    def getValue: java.time.Duration = value.asJava
  }
  @ApiMayChange
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
