/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongUnaryOperator

import akka.annotation.InternalApi

/**
 * Always increasing wall clock time.
 */
@InternalApi
private[akka] final class AlwaysIncreasingClock() extends AtomicLong with WallClock {

  override def currentTimeMillis(): Long = {
    val currentSystemTime = System.currentTimeMillis()
    updateAndGet {
      new LongUnaryOperator {
        override def applyAsLong(time: Long): Long = {
          if (currentSystemTime <= time) time + 1
          else currentSystemTime
        }
      }
    }
  }
}
