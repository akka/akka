/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.javadsl

/**
 * Implement this interface in the [[DurableStateBehavior]] to store additional change event when
 * the state is updated. The event can be used in Projections.
 */
trait ChangeEventHandler[State, ChangeEvent] {

  /**
   * Store additional change event when the state is updated. The event can be used in Projections.
   *
   * @param previousState Previous state before the update.
   * @param newState      New state after the update.
   * @return The change event to be stored.
   */
  def changeEvent(previousState: State, newState: State): ChangeEvent

  /**
   * Store additional change event when the state is updated. The event can be used in Projections.
   *
   * @param previousState Previous state before the delete.
   * @return The change event to be stored.
   */
  def deleteChangeEvent(previousState: State): ChangeEvent

}
