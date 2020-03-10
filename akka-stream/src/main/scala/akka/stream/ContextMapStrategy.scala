/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

object ContextMapStrategy {
  //trait Strategy[In, Ctx, Out]

  /**
   * Iterate over each element and transform the context given the element [[In]], source context [[Ctx]], index, and
   * if the there is a next output element [[Out]]. If there are not output elements then we can emit a single output
   * element [[Out]] and output context [[Ctx]] that represents this state.  This strategy can be used to satisfy all
   * 1:many and 1:0 use cases.
   */
  final case class Iterate[In, Ctx, Out](
                                          iterateFn: (In, Ctx, Out, Int, Boolean) => Ctx,
                                          emptyFn: Option[(In, Ctx) => (Out, Ctx)] = None
                                        )// extends Strategy[In, Ctx, Out]

  /**
   * Passthrough the same input context [[Ctx] of the input element [[In]] to all output elements [[Out]].
   */
  def same[In, Ctx, Out](): Iterate[In, Ctx, Out] = Iterate(
    iterateFn = (_: In, inCtx: Ctx, _: Out, _: Int, _: Boolean) => inCtx
  )

  /**
   * Transform the context of the first element given the input element [[In]] and input context [[Ctx]].
   */
  def first[In, Ctx, Out](firstFn: (In, Ctx, Out) => Ctx): Iterate[In, Ctx, Out] = Iterate(
    iterateFn = (in: In, inCtx: Ctx, out: Out, index: Int, _: Boolean) =>
      if (index == 0) firstFn(in, inCtx, out)
      else inCtx
  )

  //final case class First[In, InCtx, Out, OutCtx](f: (In, InCtx, Out) => OutCtx) extends Strategy[In, InCtx, Out, OutCtx]

  /**
   * Transform the context of the last element given the input element [[In]], input context [[InCtx]], and the index
   * of the element.
   */
  def last[In, InCtx, Out](fn: (In, InCtx, Out, Int) => InCtx): Iterate[In, InCtx, Out] = Iterate(
    iterateFn = (in: In, inCtx: InCtx, out: Out, index: Int, hasNext: Boolean) =>
      if (!hasNext) fn(in, inCtx, out, index)
      else inCtx
  )

  //final case class Last[In, InCtx, Out, OutCtx](f: (In, InCtx, Out, Int) => OutCtx) extends Strategy[In, InCtx, Out, OutCtx]

  def iterate[In, Ctx, Out](fn: (In, Ctx, Out, Int, Boolean) => Ctx): Iterate[In, Ctx, Out] = Iterate(fn)

  def iterateOrEmpty[In, Ctx, Out](
                                    fn: (In, Ctx, Out, Int, Boolean) => Ctx,
                                    emptyFn: (In, Ctx) => (Out, Ctx)
                                  ): Iterate[In, Ctx, Out] = Iterate(iterateFn = fn, emptyFn = Some(emptyFn))

//  /**
//   * Iterate over each element and transform the context given the element [[In]], source context [[InCtx]], index, and
//   * if the there is a next output element [[Out]]. This strategy can be used to satisfy all 1:many use cases.
//   */
//  final case class Iterate[In, InCtx, Out, OutCtx](f: (In, InCtx, Int, Boolean) => OutCtx) extends Strategy[In, InCtx, Out, OutCtx]


}
