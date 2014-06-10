/*
 * Copyright (c) 2011 Miles Sabin 
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

object tag {
  def apply[U] = new Tagger[U]

  trait Tagged[U]
  type @@[+T, U] = T with Tagged[U]

  class Tagger[U] {
    def apply[T](t: T): T @@ U = t.asInstanceOf[T @@ U]
  }
}

object newtype {
  /**
   * Creates a value of the newtype given a value of its representation type.
   */
  def apply[Repr, Ops](r: Repr): Newtype[Repr, Ops] = r.asInstanceOf[Any with Newtype[Repr, Ops]]

  /**
   * New type with `Repr` as representation type and operations provided by `Ops`.
   *
   * Values of the newtype will not add any additional boxing beyond what's required for
   * values of the representation type to conform to Any. In practice this means that value
   * types will receive their standard Scala AnyVal boxing and reference types will be unboxed.
   */
  type Newtype[Repr, Ops] = { type Tag = NewtypeTag[Repr, Ops] }
  trait NewtypeTag[Repr, Ops]

  /**
   * Implicit conversion of newtype to `Ops` type for the selection of `Ops` newtype operations.
   *
   * The implicit conversion `Repr => Ops` would typically be provided by publishing the companion
   * object of the `Ops` type as an implicit value.
   */
  implicit def newtypeOps[Repr, Ops](t: Newtype[Repr, Ops])(implicit mkOps: Repr ⇒ Ops): Ops = t.asInstanceOf[Repr]
}

/**
 * Type class witnessing the least upper bound of a pair of types and providing conversions from each to their common
 * supertype.
 *
 * @author Miles Sabin
 */
trait Lub[-A, -B, +Out] {
  def left(a: A): Out
  def right(b: B): Out
}

object Lub {
  implicit def lub[T] = new Lub[T, T, T] {
    def left(a: T): T = a
    def right(b: T): T = b
  }
}

/**
 * Type class witnessing that type `P` is equal to `F[T]` for some higher kinded type `F[_]` and type `T`.
 *
 * @author Miles Sabin
 */
trait Unpack1[-P, F[_], T]

object Unpack1 {
  implicit def unpack1[F[_], T]: Unpack1[F[T], F, T] = new Unpack1[F[T], F, T] {}
}

/**
 * Type class witnessing that type `P` is equal to `F[T, U]` for some higher kinded type `F[_, _]` and types `T` and `U`.
 *
 * @author Miles Sabin
 */
trait Unpack2[-P, F[_, _], T, U]

object Unpack2 {
  implicit def unpack2[F[_, _], T, U]: Unpack2[F[T, U], F, T, U] = new Unpack2[F[T, U], F, T, U] {}
}
