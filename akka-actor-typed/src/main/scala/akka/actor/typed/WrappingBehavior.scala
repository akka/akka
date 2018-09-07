/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

/**
 * Behaviors that wrap other behaviors must some times be traversed to look through the stack of behaviors,
 * for example to deduplicate wrapping behaviors. They should therefore implement this method.
 */
trait WrappingBehavior[T] {
  /**
   * @return The behavior that is wrapped by this behavior
   */
  def nestedBehavior: Behavior[T]
  /**
   * Replace the behavior that is wrapped by this behavior with a new nested behavior
   * @return a new instance of this wrapping behavior with `newNested` as nestedBehavior
   */
  def replaceNested(newNested: Behavior[T]): Behavior[T]
}

/**
 * Identifies (through instance equality) when nested wrapped behaviors are the same logical behavior, and instances
 * further out in the behavior stack can be eliminated to avoid infinite behavior stack
 * build up (and stack overflow).
 *
 * In cases where no recursive behavior build up happens, you can use a new instance each time, in cases where
 * a behavior may return a new behavior with the same wrapping behavior, define a constant instance and use that.
 */
final class WrappedBehaviorId
