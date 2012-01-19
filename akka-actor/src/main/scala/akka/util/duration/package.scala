/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import java.util.concurrent.TimeUnit

package object duration {
  trait Classifier[C] {
    type R
    def convert(d: Duration): R
  }

  object span
  implicit object spanConvert extends Classifier[span.type] {
    type R = Duration
    def convert(d: Duration) = d
  }

  object fromNow
  implicit object fromNowConvert extends Classifier[fromNow.type] {
    type R = Deadline
    def convert(d: Duration) = Deadline.now + d
  }

  implicit def intToDurationInt(n: Int) = new DurationInt(n)
  implicit def longToDurationLong(n: Long) = new DurationLong(n)
  implicit def doubleToDurationDouble(d: Double) = new DurationDouble(d)

  implicit def pairIntToDuration(p: (Int, TimeUnit)) = Duration(p._1, p._2)
  implicit def pairLongToDuration(p: (Long, TimeUnit)) = Duration(p._1, p._2)
  implicit def durationToPair(d: Duration) = (d.length, d.unit)

  implicit def intMult(i: Int) = new {
    def *(d: Duration) = d * i
  }
  implicit def longMult(l: Long) = new {
    def *(d: Duration) = d * l
  }
  implicit def doubleMult(f: Double) = new {
    def *(d: Duration) = d * f
  }
}
