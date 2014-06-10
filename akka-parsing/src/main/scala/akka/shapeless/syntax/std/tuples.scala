/*
 * Copyright (c) 2013 Miles Sabin 
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
package std

trait LowPriorityTuple {
  implicit def productTupleOps[P <: Product](p: P): TupleOps[P] = new TupleOps(p)
}

object tuple extends LowPriorityTuple {
  implicit def unitTupleOps(u: Unit): TupleOps[Unit] = new TupleOps(u)

  // Duplicated here from shapeless.HList so that explicit imports of tuple._ don't
  // clobber the conversion to HListOps.
  implicit def hlistOps[L <: HList](l: L): HListOps[L] = new HListOps(l)
}

final class TupleOps[T](t: T) {
  import ops.tuple._

  /**
   * Returns an `HList` containing the elements of this tuple.
   */
  def productElements(implicit gen: Generic[T]): gen.Repr = gen.to(t)

  /**
   * Returns the first element of this tuple.
   */
  def head(implicit c: IsComposite[T]): c.H = c.head(t)

  /**
   * Returns that tail of this tuple. Available only if there is evidence that this tuple is composite.
   */
  def tail(implicit c: IsComposite[T]): c.T = c.tail(t)

  /**
   * Prepend the argument element to this tuple.
   */
  def +:[E](e: E)(implicit prepend: Prepend[Tuple1[E], T]): prepend.Out = prepend(Tuple1(e), t)

  /**
   * Append the argument element to this tuple.
   */
  def :+[E](e: E)(implicit prepend: Prepend[T, Tuple1[E]]): prepend.Out = prepend(t, Tuple1(e))

  /**
   * Append the argument tuple to this tuple.
   */
  def ++[U](u: U)(implicit prepend: Prepend[T, U]): prepend.Out = prepend(t, u)

  /**
   * Prepend the argument tuple to this tuple.
   */
  def ++:[U](u: U)(implicit prepend: Prepend[U, T]): prepend.Out = prepend(u, t)

  /**
   * Prepend the argument tuple to this tuple.
   */
  def :::[U](u: U)(implicit prepend: Prepend[U, T]): prepend.Out = prepend(u, t)

  /**
   * Prepend the reverse of the argument tuple to this tuple.
   */
  def reverse_:::[U](u: U)(implicit prepend: ReversePrepend[U, T]): prepend.Out = prepend(u, t)

  /**
   * Returns the ''nth'' element of this tuple. An explicit type argument must be provided. Available only if there is
   * evidence that this tuple has at least ''n'' elements.
   */
  def apply[N <: Nat](implicit at: At[T, N]): at.Out = at(t)

  /**
   * Returns the ''nth'' element of this tuple. Available only if there is evidence that this tuple has at least ''n''
   * elements.
   */
  def apply(n: Nat)(implicit at: At[T, n.N]): at.Out = at(t)

  /**
   * Returns the ''nth'' element of this tuple. An explicit type argument must be provided. Available only if there is
   * evidence that this tuple has at least ''n'' elements.
   */
  def at[N <: Nat](implicit at: At[T, N]): at.Out = at(t)

  /**
   * Returns the ''nth'' element of this tuple. Available only if there is evidence that this tuple has at least ''n''
   * elements.
   */
  def at(n: Nat)(implicit at: At[T, n.N]): at.Out = at(t)

  /**
   * Returns the last element of this tuple. Available only if there is evidence that this tuple is composite.
   */
  def last(implicit last: Last[T]): last.Out = last(t)

  /**
   * Returns a tuple consisting of all the elements of this tuple except the last. Available only if there is
   * evidence that this tuple is composite.
   */
  def init(implicit init: Init[T]): init.Out = init(t)

  /**
   * Returns the first element of type `U` of this tuple. An explicit type argument must be provided. Available only
   * if there is evidence that this tuple has an element of type `U`.
   */
  def select[U](implicit selector: Selector[T, U]): selector.Out = selector(t)

  /**
   * Returns all elements of type `U` of this tuple. An explicit type argument must be provided.
   */
  def filter[U](implicit filter: Filter[T, U]): filter.Out = filter(t)

  /**
   * Returns all elements of type different than `U` of this tuple. An explicit type argument must be provided.
   */
  def filterNot[U](implicit filterNot: FilterNot[T, U]): filterNot.Out = filterNot(t)

  /**
   * Returns the first element of type `U` of this tuple plus the remainder of the tuple. An explicit type argument
   * must be provided. Available only if there is evidence that this tuple has an element of type `U`.
   *
   * The `Elem` suffix is here for consistency with the corresponding method name for `HList` and should be
   * removed when the latter is removed.
   */
  def removeElem[U](implicit remove: Remove[T, U]): remove.Out = remove(t)

  /**
   * Returns the first elements of this tuple that have types in `S` plus the remainder of the tuple. An expicit
   * type argument must be provided. Available only if there is evidence that this tuple contains elements with
   * types in `S`.
   */
  def removeAll[S](implicit removeAll: RemoveAll[T, S]): removeAll.Out = removeAll(t)

  /**
   * Replaces the first element of type `U` of this tuple with the supplied value, also of type `U` returning both
   * the replaced element and the updated tuple. Available only if there is evidence that this tuple has an element
   * of type `U`.
   */
  def replace[U](u: U)(implicit replacer: Replacer[T, U, U]): replacer.Out = replacer(t, u)

  class ReplaceTypeAux[U] {
    def apply[V](v: V)(implicit replacer: Replacer[T, V, U]): replacer.Out = replacer(t, v)
  }

  /**
   * Replaces the first element of type `U` of this tuple with the supplied value of type `V`, returning both the
   * replaced element and the updated tuple. An explicit type argument must be provided for `U`. Available only if
   * there is evidence that this tuple has an element of type `U`.
   */
  def replaceType[U] = new ReplaceTypeAux[U]

  /**
   * Replaces the first element of type `U` of this tuple with the supplied value, also of type `U`. Available only
   * if there is evidence that this tuple has an element of type `U`.
   *
   * The `Elem` suffix is here for consistency with the corresponding method name for `HList` and should be
   * removed when the latter is removed.
   */
  def updatedElem[U, R](u: U)(implicit replacer: Replacer.Aux[T, U, U, (U, R)]): R = replacer(t, u)._2

  class UpdatedTypeAux[U] {
    def apply[V, R](v: V)(implicit replacer: Replacer.Aux[T, V, U, (U, R)]): R = replacer(t, v)._2
  }

  /**
   * Replaces the first element of type `U` of this tuple with the supplied value of type `V`. An explicit type
   * argument must be provided for `U`. Available only if there is evidence that this tuple has an element of
   * type `U`.
   */
  def updatedType[U] = new UpdatedTypeAux[U]

  class UpdatedAtAux[N <: Nat] {
    def apply[U, V, R](u: U)(implicit replacer: ReplaceAt.Aux[T, N, U, (V, R)]): R = replacer(t, u)._2
  }

  /**
   * Replaces the ''nth' element of this tuple with the supplied value of type `U`. An explicit type argument
   * must be provided for `N`. Available only if there is evidence that this tuple has at least ''n'' elements.
   */
  def updatedAt[N <: Nat] = new UpdatedAtAux[N]

  /**
   * Replaces the ''nth' element of this tuple with the supplied value of type `U`. Available only if there is
   * evidence that this tuple has at least ''n'' elements.
   */
  def updatedAt[U, V, R](n: Nat, u: U)(implicit replacer: ReplaceAt.Aux[T, n.N, U, (V, R)]): R = replacer(t, u)._2

  /**
   * Returns the first ''n'' elements of this tuple. An explicit type argument must be provided. Available only if
   * there is evidence that this tuple has at least ''n'' elements.
   */
  def take[N <: Nat](implicit take: Take[T, N]): take.Out = take(t)

  /**
   * Returns the first ''n'' elements of this tuple. Available only if there is evidence that this tuple has at
   * least ''n'' elements.
   */
  def take(n: Nat)(implicit take: Take[T, n.N]): take.Out = take(t)

  /**
   * Returns all but the  first ''n'' elements of this tuple. An explicit type argument must be provided. Available
   * only if there is evidence that this tuple has at least ''n'' elements.
   */
  def drop[N <: Nat](implicit drop: Drop[T, N]): drop.Out = drop(t)

  /**
   * Returns all but the  first ''n'' elements of this tuple. Available only if there is evidence that this tuple
   * has at least ''n'' elements.
   */
  def drop(n: Nat)(implicit drop: Drop[T, n.N]): drop.Out = drop(t)

  /**
   * Splits this tuple at the ''nth'' element, returning the prefix and suffix as a pair. An explicit type argument
   * must be provided. Available only if there is evidence that this tuple has at least ''n'' elements.
   */
  def split[N <: Nat](implicit split: Split[T, N]): split.Out = split(t)

  /**
   * Splits this tuple at the ''nth'' element, returning the prefix and suffix as a pair. Available only if there is
   * evidence that this tuple has at least ''n'' elements.
   */
  def split(n: Nat)(implicit split: Split[T, n.N]): split.Out = split(t)

  /**
   * Splits this tuple at the ''nth'' element, returning the reverse of the prefix and suffix as a pair. An explicit
   * type argument must be provided. Available only if there is evidence that this tuple has at least ''n'' elements.
   */
  def reverse_split[N <: Nat](implicit split: ReverseSplit[T, N]): split.Out = split(t)

  /**
   * Splits this tuple at the ''nth'' element, returning the reverse of the prefix and suffix as a pair. Available
   * only if there is evidence that this tuple has at least ''n'' elements.
   */
  def reverse_split(n: Nat)(implicit split: ReverseSplit[T, n.N]): split.Out = split(t)

  /**
   * Splits this tuple at the first occurrence of an element of type `U`, returning the prefix and suffix as a pair.
   * An explicit type argument must be provided. Available only if there is evidence that this tuple has an element
   * of type `U`.
   */
  def splitLeft[U](implicit splitLeft: SplitLeft[T, U]): splitLeft.Out = splitLeft(t)

  /**
   * Splits this tuple at the first occurrence of an element of type `U`, returning reverse of the prefix and suffix
   * as a pair. An explicit type argument must be provided. Available only if there is evidence that this tuple has
   * an element of type `U`.
   */
  def reverse_splitLeft[U](implicit splitLeft: ReverseSplitLeft[T, U]): splitLeft.Out = splitLeft(t)

  /**
   * Splits this tuple at the last occurrence of an element of type `U`, returning the prefix and suffix as a pair.
   * An explicit type argument must be provided. Available only if there is evidence that this tuple has an element
   * of type `U`.
   */
  def splitRight[U](implicit splitRight: SplitRight[T, U]): splitRight.Out = splitRight(t)

  /**
   * Splits this tuple at the last occurrence of an element of type `U`, returning reverse of the prefix and suffix
   * as a pair. An explicit type argument must be provided. Available only if there is evidence that this tuple has
   * an element of type `U`.
   */
  def reverse_splitRight[U](implicit splitRight: ReverseSplitRight[T, U]): splitRight.Out = splitRight(t)

  /**
   * Reverses this tuple.
   */
  def reverse(implicit reverse: Reverse[T]): reverse.Out = reverse(t)

  /**
   * Maps a higher rank function across this tuple.
   */
  def map(f: Poly)(implicit mapper: Mapper[T, f.type]): mapper.Out = mapper(t)

  /**
   * Flatmaps a higher rank function across this tuple.
   */
  def flatMap(f: Poly)(implicit mapper: FlatMapper[T, f.type]): mapper.Out = mapper(t)

  /**
   * Replaces each element of this tuple with a constant value.
   */
  def mapConst[C](c: C)(implicit mapper: ConstMapper[T, C]): mapper.Out = mapper(t, c)

  /**
   * Maps a higher rank function ''f'' across this tuple and folds the result using monomorphic combining operator
   * `op`. Available only if there is evidence that the result type of `f` at each element conforms to the argument
   * type of ''op''.
   */
  def foldMap[R](z: R)(f: Poly)(op: (R, R) ⇒ R)(implicit folder: MapFolder[T, R, f.type]): R = folder(t, z, op)

  /**
   * Computes a left fold over this tuple using the polymorphic binary combining operator `op`. Available only if
   * there is evidence `op` can consume/produce all the partial results of the appropriate types.
   */
  def foldLeft[R](z: R)(op: Poly)(implicit folder: LeftFolder[T, R, op.type]): folder.Out = folder(t, z)

  /**
   * Computes a right fold over this tuple using the polymorphic binary combining operator `op`. Available only if
   * there is evidence `op` can consume/produce all the partial results of the appropriate types.
   */
  def foldRight[R](z: R)(op: Poly)(implicit folder: RightFolder[T, R, op.type]): folder.Out = folder(t, z)

  /**
   * Computes a left reduce over this tuple using the polymorphic binary combining operator `op`. Available only if
   * there is evidence that this tuple has at least one element and that `op` can consume/produce all the partial
   * results of the appropriate types.
   */
  def reduceLeft(op: Poly)(implicit reducer: LeftReducer[T, op.type]): reducer.Out = reducer(t)

  /**
   * Computes a right reduce over this tuple using the polymorphic binary combining operator `op`. Available only if
   * there is evidence that this tuple has at least one element and that `op` can consume/produce all the partial
   * results of the appropriate types.
   */
  def reduceRight(op: Poly)(implicit reducer: RightReducer[T, op.type]): reducer.Out = reducer(t)

  /**
   * Zips this tuple with its argument tuple returning a tuple of pairs.
   */
  def zip[R](r: R)(implicit transpose: Transposer[(T, R)]): transpose.Out = transpose((t, r))

  /**
   * Zips this tuple of monomorphic function values with its argument tuple of correspondingly typed function
   * arguments returning the result of each application as a tuple. Available only if there is evidence that the
   * corresponding function and argument elements have compatible types.
   */
  def zipApply[A](a: A)(implicit zipper: ZipApply[T, A]): zipper.Out = zipper(t, a)

  /**
   * Zips this tuple of tuples returning a tuple of tuples. Available only if there is evidence that this
   * tuple has tuple elements.
   */
  def zip(implicit transpose: Transposer[T]): transpose.Out = transpose(t)

  /**
   * Unzips this tuple of tuples returning a tuple of tuples. Available only if there is evidence that this
   * tuple has tuple elements.
   */
  def unzip(implicit transpose: Transposer[T]): transpose.Out = transpose(t)

  /**
   * Zips this tuple with its argument tuple of tuples, returning a tuple of tuples with each element of
   * this tuple prepended to the corresponding tuple element of the argument tuple.
   */
  def zipOne[R](r: R)(implicit zipOne: ZipOne[T, R]): zipOne.Out = zipOne(t, r)

  /**
   * Zips this tuple with a constant, resulting in a tuple of tuples, with each element being of the form
   * ({element from original tuple}, {supplied constant})
   */
  def zipConst[C](c: C)(implicit zipper: ZipConst[T, C]): zipper.Out = zipper(t, c)

  /**
   * Transposes this tuple.
   */
  def transpose(implicit transpose: Transposer[T]): transpose.Out = transpose(t)

  /**
   * Returns a tuple typed as a repetition of the least upper bound of the types of the elements of this tuple.
   */
  def unify(implicit unifier: Unifier[T]): unifier.Out = unifier(t)

  /**
   * Returns a tuple with all elements that are subtypes of `B` typed as `B`.
   */
  def unifySubtypes[B](implicit subtypeUnifier: SubtypeUnifier[T, B]): subtypeUnifier.Out = subtypeUnifier(t)

  /**
   * Compute the length of this tuple.
   */
  def length(implicit length: Length[T]): length.Out = length(t)

  /**
   * Converts this tuple to a `List` of elements typed as the least upper bound of the types of the elements
   * of this tuple.
   */
  def toList[Lub](implicit toList: ToList[T, Lub]): toList.Out = toList(t)

  /**
   * Converts this tuple to an `Array` of elements typed as the least upper bound of the types of the elements
   * of this tuple.
   *
   * It is advisable to specify the type parameter explicitly, because for many reference types, case classes in
   * particular, the inferred type will be too precise (ie. `Product with Serializable with CC` for a typical case class
   * `CC`) which interacts badly with the invariance of `Array`s.
   */
  def toArray[Lub](implicit toArray: ToArray[T, Lub]): toArray.Out = toArray(t)
}
