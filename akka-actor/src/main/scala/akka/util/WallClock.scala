/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.annotation.ApiMayChange

/**
 * A time source.
 */
@ApiMayChange
trait WallClock {
  def currentTimeMillis(): Long
}

object WallClock {

  /**
   * Always increasing time source.
   */
  val AlwaysIncreasingClock: WallClock = new AlwaysIncreasingClock()
}
