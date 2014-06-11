/*
 * Copyright (c) 2011-13 Miles Sabin 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.shapeless

import scala.collection.{ GenTraversable, GenTraversableLike }
import scala.collection.generic.{ CanBuildFrom, IsTraversableLike }

/**
 * Wrapper for a collection type witnessing that it has the statically specified length. Can be
 * applied to any type which can be viewed as a `GenTraversableLike`, ie. standard collections,
 * `Array`s, `String`s etc.
 *
 * @author Miles Sabin
 */
final class Sized[+Repr, L <: Nat](val unsized: Repr) extends AnyVal

/**
 * Carrier for `Sized` operations.
 *
 * These operations are implemented here as extension methods of the minimal `Sized` type to avoid issues that would
 * otherwise be caused by its covariance.
 *
 * @author Miles Sabin
 */
class SizedOps[A, Repr, L <: Nat](r: GenTraversableLike[A, Repr]) { outer ⇒
  import nat._
  import ops.nat._
  import LT._
  import Sized.wrap

  /**
   * Returns the head of this collection. Available only if there is evidence that this collection has at least one
   * element.
   */
  def head(implicit ev: _0 < L): A = r.head

  /**
   * Returns the tail of this collection. Available only if there is evidence that this collection has at least one
   * element.
   */
  def tail(implicit pred: Pred[L]) = wrap[Repr, pred.Out](r.tail)

  /**
   * Returns the first ''m'' elements of this collection. An explicit type argument must be provided. Available only if
   * there is evidence that this collection has at least ''m'' elements. The resulting collection will be statically
   * known to have ''m'' elements.
   */
  def take[M <: Nat](implicit diff: Diff[L, M], ev: ToInt[M]) = wrap[Repr, M](r.take(toInt[M]))

  /**
   * Returns the first ''m'' elements of this collection. Available only if there is evidence that this collection has
   * at least ''m'' elements. The resulting collection will be statically known to have ''m'' elements.
   */
  def take(m: Nat)(implicit diff: Diff[L, m.N], ev: ToInt[m.N]) = wrap[Repr, m.N](r.take(toInt[m.N]))

  /**
   * Returns all but the  first ''m'' elements of this collection. An explicit type argument must be provided. Available
   * only if there is evidence that this collection has at least ''m'' elements. The resulting collection will be
   * statically known to have ''m'' less elements than this collection.
   */
  def drop[M <: Nat](implicit diff: Diff[L, M], ev: ToInt[M]) = wrap[Repr, diff.Out](r.drop(toInt[M]))

  /**
   * Returns all but the  first ''m'' elements of this collection. Available only if there is evidence that this
   * collection has at least ''m'' elements. The resulting collection will be statically known to have ''m'' less
   * elements than this collection.
   */
  def drop(m: Nat)(implicit diff: Diff[L, m.N], ev: ToInt[m.N]) = wrap[Repr, diff.Out](r.drop(toInt[m.N]))

  /**
   * Splits this collection at the ''mth'' element, returning the prefix and suffix as a pair. An explicit type argument
   * must be provided. Available only if there is evidence that this collection has at least ''m'' elements. The
   * resulting collections will be statically know to have ''m'' and ''n-m'' elements respectively.
   */
  def splitAt[M <: Nat](implicit diff: Diff[L, M], ev: ToInt[M]) = (take[M], drop[M])

  /**
   * Splits this collection at the ''mth'' element, returning the prefix and suffix as a pair. Available only if there
   * is evidence that this collection has at least ''m'' elements. The resulting collections will be statically know to
   * have ''m'' and ''n-m'' elements respectively.
   */
  def splitAt(m: Nat)(implicit diff: Diff[L, m.N], ev: ToInt[m.N]) = (take[m.N], drop[m.N])

  /**
   * Prepend the argument element to this collection. The resulting collection will be statically known to have a size
   * one greater than this collection.
   */
  def +:(elem: A)(implicit cbf: CanBuildFrom[Repr, A, Repr]) = {
    val builder = cbf.apply(r.repr)
    builder += elem
    builder ++= r.toIterator
    wrap[Repr, Succ[L]](builder.result)
  }

  /**
   * Append the argument element to this collection. The resulting collection will be statically known to have a size
   * one greater than this collection.
   */
  def :+(elem: A)(implicit cbf: CanBuildFrom[Repr, A, Repr]) = {
    val builder = cbf.apply(r.repr)
    builder ++= r.toIterator
    builder += elem
    wrap[Repr, Succ[L]](builder.result)
  }

  /**
   * Append the argument collection to this collection. The resulting collection will be statically known to have
   * ''m+n'' elements.
   */
  def ++[B >: A, That, M <: Nat](that: Sized[That, M])(implicit sum: Sum[L, M],
                                                       cbf: CanBuildFrom[Repr, B, That],
                                                       convThat: That ⇒ GenTraversableLike[B, That]) = wrap[That, sum.Out](r ++ that.unsized)

  /**
   * Map across this collection. The resulting collection will be statically known to have the same number of elements
   * as this collection.
   */
  def map[B, That](f: A ⇒ B)(implicit cbf: CanBuildFrom[Repr, B, That]) = wrap[That, L](r map f)
}

trait LowPrioritySized {
  implicit def sizedToRepr[Repr](s: Sized[Repr, _]): Repr = s.unsized
}

object Sized extends LowPrioritySized {
  implicit def sizedOps[Repr, L <: Nat](s: Sized[Repr, L])(implicit itl: IsTraversableLike[Repr]): SizedOps[itl.A, Repr, L] =
    new SizedOps[itl.A, Repr, L](itl.conversion(s.unsized))

  def apply[CC[_]] = new SizedBuilder[CC]

  def apply[CC[_]]()(implicit cbf: CanBuildFrom[Nothing, Nothing, CC[Nothing]]) =
    new Sized[CC[Nothing], _0](cbf().result)

  def wrap[Repr, L <: Nat](r: Repr) = new Sized[Repr, L](r)

  def unapplySeq[Repr, L <: Nat](x: Sized[Repr, L]) = Some(x.unsized)
}
