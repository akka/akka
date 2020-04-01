/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

object ContextMapStrategy {

  final case class Iterate[In, Ctx, Out](
      iterateFn: (In, Ctx, Out, Long, Boolean) => Ctx,
      emptyFn: Option[(In, Ctx) => (Out, Ctx)] = None)

  /**
   * Passthrough the same context [[Ctx] of the input element [[In]] to all output elements [[Out]].
   */
  def same[In, Ctx, Out](): Iterate[In, Ctx, Out] = Iterate(
    iterateFn = (_: In, inCtx: Ctx, _: Out, _: Long, _: Boolean) => inCtx
  )

  /**
   * Transform the context of the first element given the input element [[In]] and context [[Ctx]].
   */
  def first[In, Ctx, Out](firstFn: (In, Ctx, Out) => Ctx): Iterate[In, Ctx, Out] = Iterate(
    iterateFn = (in: In, inCtx: Ctx, out: Out, index: Long, _: Boolean) =>
      if (index == 0) firstFn(in, inCtx, out)
      else inCtx
  )

  /**
   * Transform the context of the last element given the input element [[In]], context [[Ctx]], and the index
   * of the element.
   */
  def last[In, Ctx, Out](fn: (In, Ctx, Out, Long) => Ctx): Iterate[In, Ctx, Out] = Iterate(
    iterateFn = (in: In, inCtx: Ctx, out: Out, index: Long, hasNext: Boolean) =>
      if (!hasNext) fn(in, inCtx, out, index)
      else inCtx
  )

  /**
   * Iterate over each element and transform the context given the element [[In]], context [[InCtx]],
   * output element [[Out]], index, and if the there is a next output element [[Out]]. This strategy can be used to
   * satisfy all 1:many use cases.
   */
  def iterate[In, Ctx, Out](fn: (In, Ctx, Out, Long, Boolean) => Ctx): Iterate[In, Ctx, Out] = Iterate(fn)

  /**
   * Iterate over each element and transform the context given the element [[In]], context [[InCtx]],
   * output element [[Out]], index, and if the there is a next output element [[Out]]. If there are no output elements
   * then we can emit a single output element [[Out]] and output context [[Ctx]] that represents this state.
   * This strategy can be used to satisfy all 1:many and 1:0 use cases.
   */
  def iterateOrEmpty[In, Ctx, Out](
                                    fn: (In, Ctx, Out, Long, Boolean) => Ctx,
                                    emptyFn: (In, Ctx) => (Out, Ctx)
                                  ): Iterate[In, Ctx, Out] = Iterate(iterateFn = fn, emptyFn = Some(emptyFn))
}
