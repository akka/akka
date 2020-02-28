/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

object ContextMapStrategy {
  sealed trait Strategy[In, InCtx, OutCtx]

  /**
   * All output elements receive the same context.
   */
  case class Same[In, InCtx, OutCtx](f: (In, InCtx) => OutCtx) extends Strategy[In, InCtx, OutCtx]

//  /**
//   * Transform the context of the last element given the input element [[In]], input context [[InCtx]], and the index
//   * of the element.
//   */
//  final case class Last[In, InCtx, OutCtx](f: (In, InCtx, Int) => OutCtx) extends Strategy[In, InCtx, OutCtx, Nothing]

  /**
   * Iterate over each element and transform the context given the element [[In]], source context [[InCtx]], index, and
   * if the [[Iterable]] has a next element.
   */
  final case class Iterate[In, InCtx, OutCtx](f: (In, InCtx, Int, Boolean) => OutCtx) extends Strategy[In, InCtx, OutCtx]

  /**
   * Iterate over each element and transform the context given the element [[In]], source context [[InCtx]], index, and
   * if the [[Iterable]] has a next element.
   *
   * In cases where there is only 1 element, the [[only]] UDF is called after [[iterate]].
   * In cases where there are no elements, the [[none]] UDF is called.
   */
  final case class All[In, InCtx, OutCtx](
                                                    iterate: (In, InCtx, Int, Boolean) => OutCtx,
                                                    only: (In, InCtx) => OutCtx
                                                    //none: (In, InCtx) => (NoneOut, OutCtx)
                                                  ) extends Strategy[In, InCtx, OutCtx]

//  final case class Foo[In, InCtx, OutCtx](something: (In, InCtx, Int) => OutCtx) extends Strategy[In, InCtx, OutCtx, Nothing]
}
