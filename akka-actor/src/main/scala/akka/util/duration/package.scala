/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.TimeUnit
//FIXME Needs docs
package object duration {
  trait Classifier[C] {
    type R
    def convert(d: FiniteDuration): R
  }

  object span
  implicit object spanConvert extends Classifier[span.type] {
    type R = FiniteDuration
    def convert(d: FiniteDuration): FiniteDuration = d
  }

  object fromNow
  implicit object fromNowConvert extends Classifier[fromNow.type] {
    type R = Deadline
    def convert(d: FiniteDuration): Deadline = Deadline.now + d
  }

  implicit def intToDurationInt(n: Int): DurationInt = new DurationInt(n)
  implicit def longToDurationLong(n: Long): DurationLong = new DurationLong(n)
  implicit def doubleToDurationDouble(d: Double): DurationDouble = new DurationDouble(d)

  implicit def pairIntToDuration(p: (Int, TimeUnit)): FiniteDuration = Duration(p._1, p._2)
  implicit def pairLongToDuration(p: (Long, TimeUnit)): FiniteDuration = Duration(p._1, p._2)
  implicit def durationToPair(d: Duration): (Long, TimeUnit) = (d.length, d.unit)

  /*
   * avoid reflection based invocation by using non-duck type
   */
  class IntMult(i: Int) { def *(d: Duration): Duration = d * i }
  implicit def intMult(i: Int): IntMult = new IntMult(i)

  class LongMult(l: Long) { def *(d: Duration): Duration = d * l }
  implicit def longMult(l: Long): LongMult = new LongMult(l)

  class DoubleMult(f: Double) { def *(d: Duration): Duration = d * f }
  implicit def doubleMult(f: Double): DoubleMult = new DoubleMult(f)
}
