/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

object CachedSystemTimer {
  private var notAccuracyCounter = 1000
  private final val FORCE_UPDATE_THRESHOLD = 1000
  import scala.concurrent.duration._
  private final val ONE_MILLI_IN_NANOS: Int = 1.milli.toNanos.toInt
  private final val cachedCurrentTimeMillisRef = new AtomicLong()
  private final val updater = new Thread(new Runnable {
    override def run(): Unit = {
      while (true) {
        val updated = System.currentTimeMillis()
        cachedCurrentTimeMillisRef.set(updated)
        LockSupport.parkNanos(ONE_MILLI_IN_NANOS)
      }
    }
  })
  updater.setDaemon(true)
  updater.setName("CachedSystemTimer-Updater-Thread")
  updater.start()

  /**
   * Return a cached timestamp of the current time in millis which may or may not that accuracy.
   * @return The cached current timestamp.
   * */
  def currentTimeMillis(): Long = {
    notAccuracyCounter += 1
    if (notAccuracyCounter > FORCE_UPDATE_THRESHOLD) {
      val updated = System.currentTimeMillis()
      cachedCurrentTimeMillisRef.set(updated)
      notAccuracyCounter = 0
      updated
    } else {
      cachedCurrentTimeMillisRef.get()
    }
  }
}
