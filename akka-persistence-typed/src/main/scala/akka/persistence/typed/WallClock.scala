/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

/**
 * A time source.
 */
trait WallClock {
  def currentTimeMillis(): Long
}

object WallClock {

  /**
   * Always increasing time source.
   */
  val AlwaysIncreasingClock: WallClock = new internal.AlwaysIncreasingClock()
}
