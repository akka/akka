/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.javadsl

import akka.annotation.InternalApi

/**
 * Implement this interface and use it in [[DurableStateBehavior#withChangeEventHandler]]
 * to store additional change event when the state is updated. The event can be used in Projections.
 */
trait ChangeEventHandler[Command, State, ChangeEvent] {

  /**
   * Store additional change event when the state is updated. The event can be used in Projections.
   *
   * @param previousState Previous state before the update.
   * @param newState      New state after the update.
   * @return The change event to be stored.
   */
  def changeEvent(previousState: State, newState: State, command: Command): ChangeEvent

  /**
   * Store additional change event when the state is updated. The event can be used in Projections.
   *
   * @param previousState Previous state before the delete.
   * @return The change event to be stored.
   */
  def deleteChangeEvent(previousState: State, command: Command): ChangeEvent

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ChangeEventHandler {
  val Undefined: ChangeEventHandler[Any, Any, Any] = new ChangeEventHandler[Any, Any, Any] {
    override def changeEvent(previousState: Any, newState: Any, command: Any): Any = null
    override def deleteChangeEvent(previousState: Any, command: Any): Any = null
  }

  def undefined[Command, State, ChangeEvent]: ChangeEventHandler[Command, State, ChangeEvent] =
    Undefined.asInstanceOf[ChangeEventHandler[Command, State, ChangeEvent]]

}
