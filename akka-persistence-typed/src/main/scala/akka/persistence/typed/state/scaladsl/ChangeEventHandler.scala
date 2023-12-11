/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.scaladsl

object ChangeEventHandler {

  /**
   * Define these handlers in the [[DurableStateBehavior#withChangeEventHandler]] to store additional change event when
   * the state is updated. The event can be used in Projections.
   *
   * @param updateHandler Function that given the previous and new state creates the change event to be stored
   *                      when the DurableState is updated.
   * @param deleteHandler Function that given the previous state creates the change event to be stored
   *                      when the DurableState is deleted.
   */
  def apply[Command, State, ChangeEvent](
      updateHandler: (State, State, Command) => ChangeEvent,
      deleteHandler: (State, Command) => ChangeEvent): ChangeEventHandler[Command, State, ChangeEvent] =
    new ChangeEventHandler(updateHandler, deleteHandler)
}

/**
 * Define these handlers in the [[DurableStateBehavior#withChangeEventHandler]] to store additional change event when
 * the state is updated. The event can be used in Projections.
 */
final class ChangeEventHandler[Command, State, ChangeEvent] private (
    val updateHandler: (State, State, Command) => ChangeEvent,
    val deleteHandler: (State, Command) => ChangeEvent)
