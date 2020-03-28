/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

/**
 * A ContextMapStrategy defines how the context parameter of a FlowWithContext is manipulated
 * as various operations are performed in the flow
 *
 * @typeparam Ctx the context type
 */
class ContextMapStrategy[-Ctx]

object ContextMapStrategy {
  /**
   * Trait that is mixed into strategies that allow elements (including their offsets) to be filtered out of the stream.
   */
  trait Filtering[-Ctx] {
    self: ContextMapStrategy[Ctx] =>
  }

  /**
   * Trait that is mixed into strategies that allow elements (including their offsets) to be reordered.
   */
  trait Reordering[-Ctx] {
    self: ContextMapStrategy[Ctx] =>
  }

  /**
   * Trait that is mixed into strategies that allows operations like 'mapConcat', which
   * turn a single element into zero or more elements, where it is known before the last
   * element is emitted that this is the last element.
   */
  trait Iteration[Ctx] extends Filtering[Ctx] { self: ContextMapStrategy[Ctx] =>
    def next(in: Ctx, index: Long, hasNext: Boolean): Ctx
  }
}
