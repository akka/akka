/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.stage.linkedlogic.logics

/** Link between an input and an output. */
sealed trait LogicLink {
  /** Terminates the link. */
  def remove(): Unit
}

/** Link to the outlet. */
trait OutputLink[Out] extends LogicLink {
  /**
   * Pushes the element to outlet.
   * If the outlet is not available, adds the element to the buffer.
   */
  def push(element: Out): Unit
}

/**
 * Link to the stopper.
 * Leads to the back-pressure until it is removed.
 */
trait StopperLink extends LogicLink {
}
