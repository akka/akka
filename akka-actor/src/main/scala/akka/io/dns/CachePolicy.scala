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
  final class Ttl(val value: FiniteDuration) extends CachePolicy {
    if (value <= Duration.Zero) throw new IllegalArgumentException(s"TTL values must be a positive, 32-bit integer.")
    import akka.util.JavaDurationConverters._
    def getValue: java.time.Duration = value.asJava

    def canEqual(other: Any): Boolean = other.isInstanceOf[Ttl]

    override def equals(other: Any): Boolean = other match {
      case that: Ttl ⇒
        (that canEqual this) &&
          value == that.value
      case _ ⇒ false
    }

    override def hashCode(): Int = {
      val state = Seq(value)
      state.map(_.hashCode()).foldLeft(0)((a, b) ⇒ 31 * a + b)
    }

    override def toString = s"Ttl($value)"
  }
  @ApiMayChange
  object Ttl {
    def unapply(ttl: Ttl): Option[FiniteDuration] = Some(ttl.value)
    def apply(value: FiniteDuration): Ttl = {
      new Ttl(value)
    }

    // There's places where only a Ttl makes sense (DNS RFC says TTL is a positive 32 bit integer)
    // but we know the value can be cached effectively forever (e.g. the Lookup name was the actual IP already)
    val effectivelyForever: Ttl = Ttl(Int.MaxValue.seconds)

    implicit object TtlIsOrdered extends Ordering[Ttl] {
      def compare(a: Ttl, b: Ttl) = a.value.compare(b.value)
    }
  }

}
