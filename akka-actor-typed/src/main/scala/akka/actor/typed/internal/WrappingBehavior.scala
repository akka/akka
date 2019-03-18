/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.actor.typed.Behavior
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

// FIXME #26504: see if we can completely eliminate this with the help of BehaviorInterceptor instead

/**
 * INTERNAL API
 *
 * Behaviors that wrap other behaviors must sometimes be traversed to look through the stack of behaviors,
 * for example to deduplicate wrapping behaviors. They should therefore implement this trait (interface).
 *
 * Do not implement this, instead reach for [[akka.actor.typed.BehaviorInterceptor]]
 */
@DoNotInherit
@InternalApi
private[akka] trait WrappingBehavior[O, I] {

  /**
   * @return The behavior that is wrapped by this behavior
   */
  def nestedBehavior: Behavior[I]

  /**
   * Replace the behavior that is wrapped by this behavior with a new nested behavior
   * @return a new instance of this wrapping behavior with `newNested` as nestedBehavior
   */
  def replaceNested(newNested: Behavior[I]): Behavior[O]
}
