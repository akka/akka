/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import akka.util.JavaDurationConverters._

import scala.concurrent.duration.{ Duration, FiniteDuration, _ }

object CachePolicy {

  sealed trait CachePolicy
  case object Never extends CachePolicy
  case object Forever extends CachePolicy

  final class Ttl private (val value: FiniteDuration) extends CachePolicy {
    if (value <= Duration.Zero) throw new IllegalArgumentException(s"TTL values must be a positive value.")
    import akka.util.JavaDurationConverters._
    def getValue: java.time.Duration = value.asJava

    override def equals(other: Any): Boolean = other match {
      case that: Ttl ⇒ value == that.value
      case _         ⇒ false
    }

    override def hashCode(): Int = value.hashCode()

    override def toString = s"Ttl($value)"
  }

  object Ttl {
    def unapply(ttl: Ttl): Option[FiniteDuration] = Some(ttl.value)
    def fromPositive(value: FiniteDuration): Ttl = {
      new Ttl(value)
    }
    def fromPositive(value: java.time.Duration): Ttl = fromPositive(value.asScala)

    // There's places where only a Ttl makes sense (DNS RFC says TTL is a positive 32 bit integer)
    // but we know the value can be cached effectively forever (e.g. the Lookup name was the actual IP already)
    val effectivelyForever: Ttl = fromPositive(Int.MaxValue.seconds)

    implicit object TtlIsOrdered extends Ordering[Ttl] {
      def compare(a: Ttl, b: Ttl): Int = a.value.compare(b.value)
    }
  }

}
