/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import com.codahale.metrics._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit._

class KnownOpsInTimespanCounter(expectedOps: Long) extends Metric with Counting {

  val startTime = System.nanoTime
  val stopTime = new AtomicLong(0)

  def stop() {
    stopTime.compareAndSet(0, System.nanoTime)
  }

  override def getCount: Long = expectedOps

  def elapsedTime: Long = stopTime.get - startTime

  def avgDuration: Long = (elapsedTime.toDouble / expectedOps).toLong

  def opsPerSecond: Double = expectedOps.toDouble / (elapsedTime.toDouble / NANOSECONDS.convert(1, SECONDS))

}
