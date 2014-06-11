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
package syntax

import scala.annotation.tailrec

/**
 * Carrier for `HList` operations.
 *
 * These methods are implemented here and pimped onto the minimal `HList` types to avoid issues that would otherwise be
 * caused by the covariance of `::[H, T]`.
 *
 * @author Miles Sabin
 */
final class HListOps[L <: HList](l: L) {
  import ops.hlist._

  /**
   * Returns the head of this `HList`. Available only if there is evidence that this `HList` is composite.
   */
  def head(implicit c: IsHCons[L]): c.H = c.head(l)

  /**
   * Returns the tail of this `HList`. Available only if there is evidence that this `HList` is composite.
   */
  def tail(implicit c: IsHCons[L]): c.T = c.tail(l)

  /**
   * Prepend the argument element to this `HList`.
   */
  def ::[H](h: H): H :: L = akka.shapeless.::(h, l)

  /**
   * Prepend the argument element to this `HList`.
   */
  def +:[H](h: H): H :: L = akka.shapeless.::(h, l)

  /**
   * Append the argument element to this `HList`.
   */
  def :+[T](t: T)(implicit prepend: Prepend[L, T :: HNil]): prepend.Out = prepend(l, t :: HNil)

  /**
   * Append the argument `HList` to this `HList`.
   */
  def ++[S <: HList](suffix: S)(implicit prepend: Prepend[L, S]): prepend.Out = prepend(l, suffix)

  /**
   * Prepend the argument `HList` to this `HList`.
   */
  def ++:[P <: HList](prefix: P)(implicit prepend: Prepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Prepend the argument `HList` to this `HList`.
   */
  def :::[P <: HList](prefix: P)(implicit prepend: Prepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Prepend the reverse of the argument `HList` to this `HList`.
   */
  def reverse_:::[P <: HList](prefix: P)(implicit prepend: ReversePrepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Returns the ''nth'' element of this `HList`. An explicit type argument must be provided. Available only if there is
   * evidence that this `HList` has at least ''n'' elements.
   */
  def apply[N <: Nat](implicit at: At[L, N]): at.Out = at(l)

  /**
   * Returns the ''nth'' element of this `HList`. Available only if there is evidence that this `HList` has at least ''n''
   * elements.
   */
  def apply(n: Nat)(implicit at: At[L, n.N]): at.Out = at(l)

  /**
   * Returns the ''nth'' element of this `HList`. An explicit type argument must be provided. Available only if there is
   * evidence that this `HList` has at least ''n'' elements.
   */
  def at[N <: Nat](implicit at: At[L, N]): at.Out = at(l)

  /**
   * Returns the ''nth'' element of this `HList`. Available only if there is evidence that this `HList` has at least ''n''
   * elements.
   */
  def at(n: Nat)(implicit at: At[L, n.N]): at.Out = at(l)

  /**
   * Returns the last element of this `HList`. Available only if there is evidence that this `HList` is composite.
   */
  def last(implicit last: Last[L]): last.Out = last(l)

  /**
   * Returns an `HList` consisting of all the elements of this `HList` except the last. Available only if there is
   * evidence that this `HList` is composite.
   */
  def init(implicit init: Init[L]): init.Out = init(l)

  /**
   * Returns the first element of type `U` of this `HList`. An explicit type argument must be provided. Available only
   * if there is evidence that this `HList` has an element of type `U`.
   */
  def select[U](implicit selector: Selector[L, U]): U = selector(l)

  /**
   * Returns all elements of type `U` of this `HList`. An explicit type argument must be provided.
   */
  def filter[U](implicit filter: Filter[L, U]): filter.Out = filter(l)

  /**
   * Returns all elements of type different than `U` of this `HList`. An explicit type argument must be provided.
   */
  def filterNot[U](implicit filter: FilterNot[L, U]): filter.Out = filter(l)

  /**
   * Returns the first element of type `U` of this `HList` plus the remainder of the `HList`. An explicit type argument
   * must be provided. Available only if there is evidence that this `HList` has an element of type `U`.
   *
   * The `Elem` suffix is here to avoid creating an ambiguity with RecordOps#remove and should be removed if
   * SI-5414 is resolved in a way which eliminates the ambiguity.
   */
  def removeElem[U](implicit remove: Remove[L, U]): remove.Out = remove(l)

  /**
   * Returns the first elements of this `HList` that have types in `SL` plus the remainder of the `HList`. An expicit
   * type argument must be provided. Available only if there is evidence that this `HList` contains elements with
   * types in `SL`.
   */
  def removeAll[SL <: HList](implicit removeAll: RemoveAll[L, SL]): removeAll.Out = removeAll(l)

  /**
   * Replaces the first element of type `U` of this `HList` with the supplied value, also of type `U` returning both
   * the replaced element and the updated `HList`. Available only if there is evidence that this `HList` has an element
   * of type `U`.
   */
  def replace[U](u: U)(implicit replacer: Replacer[L, U, U]): replacer.Out = replacer(l, u)

  class ReplaceTypeAux[U] {
    def apply[V](v: V)(implicit replacer: Replacer[L, U, V]): replacer.Out = replacer(l, v)
  }

  /**
   * Replaces the first element of type `U` of this `HList` with the supplied value of type `V`, returning both the
   * replaced element and the updated `HList`. An explicit type argument must be provided for `U`. Available only if
   * there is evidence that this `HList` has an element of type `U`.
   */
  def replaceType[U] = new ReplaceTypeAux[U]

  /**
   * Replaces the first element of type `U` of this `HList` with the supplied value, also of type `U`. Available only
   * if there is evidence that this `HList` has an element of type `U`.
   *
   * The `Elem` suffix is here to avoid creating an ambiguity with RecordOps#updated and should be removed if
   * SI-5414 is resolved in a way which eliminates the ambiguity.
   */
  def updatedElem[U, Out <: HList](u: U)(implicit replacer: Replacer.Aux[L, U, U, (U, Out)]): Out = replacer(l, u)._2

  class UpdatedTypeAux[U] {
    def apply[V, Out <: HList](v: V)(implicit replacer: Replacer.Aux[L, U, V, (U, Out)]): Out = replacer(l, v)._2
  }

  /**
   * Replaces the first element of type `U` of this `HList` with the supplied value of type `V`. An explicit type
   * argument must be provided for `U`. Available only if there is evidence that this `HList` has an element of
   * type `U`.
   */
  def updatedType[U] = new UpdatedTypeAux[U]

  class UpdatedAtAux[N <: Nat] {
    def apply[U, V, Out <: HList](u: U)(implicit replacer: ReplaceAt.Aux[L, N, U, (V, Out)]): Out = replacer(l, u)._2
  }

  /**
   * Replaces the ''nth' element of this `HList` with the supplied value of type `U`. An explicit type argument
   * must be provided for `N`. Available only if there is evidence that this `HList` has at least ''n'' elements.
   */
  def updatedAt[N <: Nat] = new UpdatedAtAux[N]

  /**
   * Replaces the ''nth' element of this `HList` with the supplied value of type `U`. Available only if there is
   * evidence that this `HList` has at least ''n'' elements.
   */
  def updatedAt[U, V, Out <: HList](n: Nat, u: U)(implicit replacer: ReplaceAt.Aux[L, n.N, U, (V, Out)]): Out = replacer(l, u)._2

  /**
   * Returns the first ''n'' elements of this `HList`. An explicit type argument must be provided. Available only if
   * there is evidence that this `HList` has at least ''n'' elements.
   */
  def take[N <: Nat](implicit take: Take[L, N]): take.Out = take(l)

  /**
   * Returns the first ''n'' elements of this `HList`. Available only if there is evidence that this `HList` has at
   * least ''n'' elements.
   */
  def take(n: Nat)(implicit take: Take[L, n.N]): take.Out = take(l)

  /**
   * Returns all but the  first ''n'' elements of this `HList`. An explicit type argument must be provided. Available
   * only if there is evidence that this `HList` has at least ''n'' elements.
   */
  def drop[N <: Nat](implicit drop: Drop[L, N]): drop.Out = drop(l)

  /**
   * Returns all but the  first ''n'' elements of this `HList`. Available only if there is evidence that this `HList`
   * has at least ''n'' elements.
   */
  def drop(n: Nat)(implicit drop: Drop[L, n.N]): drop.Out = drop(l)

  /**
   * Splits this `HList` at the ''nth'' element, returning the prefix and suffix as a pair. An explicit type argument
   * must be provided. Available only if there is evidence that this `HList` has at least ''n'' elements.
   */
  def split[N <: Nat](implicit split: Split[L, N]): split.Out = split(l)

  /**
   * Splits this `HList` at the ''nth'' element, returning the prefix and suffix as a pair. Available only if there is
   * evidence that this `HList` has at least ''n'' elements.
   */
  def split(n: Nat)(implicit split: Split[L, n.N]): split.Out = split(l)

  /**
   * Splits this `HList` at the ''nth'' element, returning the reverse of the prefix and suffix as a pair. An explicit
   * type argument must be provided. Available only if there is evidence that this `HList` has at least ''n'' elements.
   */
  def reverse_split[N <: Nat](implicit split: ReverseSplit[L, N]): split.Out = split(l)

  /**
   * Splits this `HList` at the ''nth'' element, returning the reverse of the prefix and suffix as a pair. Available
   * only if there is evidence that this `HList` has at least ''n'' elements.
   */
  def reverse_split(n: Nat)(implicit split: ReverseSplit[L, n.N]): split.Out = split(l)

  /**
   * Splits this `HList` at the first occurrence of an element of type `U`, returning the prefix and suffix as a pair.
   * An explicit type argument must be provided. Available only if there is evidence that this `HList` has an element
   * of type `U`.
   */
  def splitLeft[U](implicit splitLeft: SplitLeft[L, U]): splitLeft.Out = splitLeft(l)

  /**
   * Splits this `HList` at the first occurrence of an element of type `U`, returning reverse of the prefix and suffix
   * as a pair. An explicit type argument must be provided. Available only if there is evidence that this `HList` has
   * an element of type `U`.
   */
  def reverse_splitLeft[U](implicit splitLeft: ReverseSplitLeft[L, U]): splitLeft.Out = splitLeft(l)

  /**
   * Splits this `HList` at the last occurrence of an element of type `U`, returning the prefix and suffix as a pair.
   * An explicit type argument must be provided. Available only if there is evidence that this `HList` has an element
   * of type `U`.
   */
  def splitRight[U](implicit splitRight: SplitRight[L, U]): splitRight.Out = splitRight(l)

  /**
   * Splits this `HList` at the last occurrence of an element of type `U`, returning reverse of the prefix and suffix
   * as a pair. An explicit type argument must be provided. Available only if there is evidence that this `HList` has
   * an element of type `U`.
   */
  def reverse_splitRight[U](implicit splitRight: ReverseSplitRight[L, U]): splitRight.Out = splitRight(l)

  /**
   * Reverses this `HList`.
   */
  def reverse(implicit reverse: Reverse[L]): reverse.Out = reverse(l)

  /**
   * Maps a higher rank function across this `HList`.
   */
  def map(f: Poly)(implicit mapper: Mapper[f.type, L]): mapper.Out = mapper(l)

  /**
   * Flatmaps a higher rank function across this `HList`.
   */
  def flatMap(f: Poly)(implicit mapper: FlatMapper[f.type, L]): mapper.Out = mapper(l)

  /**
   * Replaces each element of this `HList` with a constant value.
   */
  def mapConst[C](c: C)(implicit mapper: ConstMapper[C, L]): mapper.Out = mapper(c, l)

  /**
   * Maps a higher rank function ''f'' across this `HList` and folds the result using monomorphic combining operator
   * `op`. Available only if there is evidence that the result type of `f` at each element conforms to the argument
   * type of ''op''.
   */
  def foldMap[R](z: R)(f: Poly)(op: (R, R) ⇒ R)(implicit folder: MapFolder[L, R, f.type]): R = folder(l, z, op)

  /**
   * Computes a left fold over this `HList` using the polymorphic binary combining operator `op`. Available only if
   * there is evidence `op` can consume/produce all the partial results of the appropriate types.
   */
  def foldLeft[R](z: R)(op: Poly)(implicit folder: LeftFolder[L, R, op.type]): folder.Out = folder(l, z)

  /**
   * Computes a right fold over this `HList` using the polymorphic binary combining operator `op`. Available only if
   * there is evidence `op` can consume/produce all the partial results of the appropriate types.
   */
  def foldRight[R](z: R)(op: Poly)(implicit folder: RightFolder[L, R, op.type]): folder.Out = folder(l, z)

  /**
   * Computes a left reduce over this `HList` using the polymorphic binary combining operator `op`. Available only if
   * there is evidence that this `HList` has at least one element and that `op` can consume/produce all the partial
   * results of the appropriate types.
   */
  def reduceLeft(op: Poly)(implicit reducer: LeftReducer[L, op.type]): reducer.Out = reducer(l)

  /**
   * Computes a right reduce over this `HList` using the polymorphic binary combining operator `op`. Available only if
   * there is evidence that this `HList` has at least one element and that `op` can consume/produce all the partial
   * results of the appropriate types.
   */
  def reduceRight(op: Poly)(implicit reducer: RightReducer[L, op.type]): reducer.Out = reducer(l)

  /**
   * Zips this `HList` with its argument `HList` returning an `HList` of pairs.
   */
  def zip[R <: HList](r: R)(implicit zipper: Zip[L :: R :: HNil]): zipper.Out = zipper(l :: r :: HNil)

  /**
   * Zips this `HList` of monomorphic function values with its argument `HList` of correspondingly typed function
   * arguments returning the result of each application as an `HList`. Available only if there is evidence that the
   * corresponding function and argument elements have compatible types.
   */
  def zipApply[A <: HList](a: A)(implicit zipper: ZipApply[L, A]): zipper.Out = zipper(l, a)

  /**
   * Zips this `HList` of `HList`s returning an `HList` of tuples. Available only if there is evidence that this
   * `HList` has `HList` elements.
   */
  def zip(implicit zipper: Zip[L]): zipper.Out = zipper(l)

  /**
   * Zips this `HList` of `HList`s returning an `HList` of tuples. Available only if there is evidence that this
   * `HList` has `HList` elements.
   */
  @deprecated("Use zip instead", "2.0.0")
  def zipped(implicit zipper: Zip[L]): zipper.Out = zipper(l)

  /**
   * Unzips this `HList` of tuples returning a tuple of `HList`s. Available only if there is evidence that this
   * `HList` has tuple elements.
   */
  def unzip(implicit unzipper: Unzip[L]): unzipper.Out = unzipper(l)

  /**
   * Unzips this `HList` of tuples returning a tuple of `HList`s. Available only if there is evidence that this
   * `HList` has tuple elements.
   */
  @deprecated("Use unzip instead", "2.0.0")
  def unzipped(implicit unzipper: Unzip[L]): unzipper.Out = unzipper(l)

  /**
   * Zips this `HList` with its argument `HList` of `HList`s, returning an `HList` of `HList`s with each element of
   * this `HList` prepended to the corresponding `HList` element of the argument `HList`.
   */
  def zipOne[T <: HList](t: T)(implicit zipOne: ZipOne[L, T]): zipOne.Out = zipOne(l, t)

  /**
   * Zips this `HList` with a constant, resulting in an `HList` of tuples of the form
   * ({element from this `HList`}, {supplied constant})
   */
  def zipConst[C](c: C)(implicit zipConst: ZipConst[C, L]): zipConst.Out = zipConst(c, l)

  /**
   * Zips this 'HList' with its argument 'HList' using argument 'Poly2', returning an 'HList'.
   * Doesn't require this to be the same length as its 'HList' argument, but does require evidence that its
   * 'Poly2' argument is defined at their intersection.
   */
  def zipWith[R <: HList, P <: Poly2](r: R)(p: P)(implicit zipWith: ZipWith[L, R, P]): zipWith.Out =
    zipWith(l, r)

  /**
   * Transposes this `HList`.
   */
  def transpose(implicit transpose: Transposer[L]): transpose.Out = transpose(l)

  /**
   * Returns an `HList` typed as a repetition of the least upper bound of the types of the elements of this `HList`.
   */
  def unify(implicit unifier: Unifier[L]): unifier.Out = unifier(l)

  /**
   * Returns an `HList` with all elements that are subtypes of `B` typed as `B`.
   */
  def unifySubtypes[B](implicit subtypeUnifier: SubtypeUnifier[L, B]): subtypeUnifier.Out = subtypeUnifier(l)

  /**
   * Converts this `HList` to a correspondingly typed tuple.
   */
  def tupled(implicit tupler: Tupler[L]): tupler.Out = tupler(l)

  /**
   * Compute the length of this `HList`.
   */
  def length(implicit length: Length[L]): length.Out = length()

  /**
   * Compute the length of this `HList` as a runtime Int value.
   */
  def runtimeLength: Int = {
    @tailrec def loop(l: HList, acc: Int): Int = l match {
      case HNil     ⇒ acc
      case hd :: tl ⇒ loop(tl, acc + 1)
    }

    loop(l, 0)
  }

  /**
   * Converts this `HList` to an ordinary `List` of elements typed as the least upper bound of the types of the elements
   * of this `HList`.
   */
  def toList[Lub](implicit toList: ToList[L, Lub]): List[Lub] = toList(l)

  /**
   * Converts this `HList` to an `Array` of elements typed as the least upper bound of the types of the elements
   * of this `HList`.
   *
   * It is advisable to specify the type parameter explicitly, because for many reference types, case classes in
   * particular, the inferred type will be too precise (ie. `Product with Serializable with CC` for a typical case class
   * `CC`) which interacts badly with the invariance of `Array`s.
   */
  def toArray[Lub](implicit toArray: ToArray[L, Lub]): Array[Lub] = toArray(runtimeLength, l, 0)

  /**
   * Converts this `HList` of values into a record with the provided keys.
   */
  def zipWithKeys[K <: HList](keys: K)(implicit withKeys: ZipWithKeys[K, L]): withKeys.Out = withKeys(keys, l)
}
