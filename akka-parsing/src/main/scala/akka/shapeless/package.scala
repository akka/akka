/*
 * Copyright (c) 2013-14 Miles Sabin 
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

package akka

package object shapeless {
  def unexpected: Nothing = sys.error("Unexpected invocation")

  // Basic definitions
  type Id[+T] = T
  type Const[C] = {
    type λ[T] = C
  }

  type ¬[T] = T ⇒ Nothing
  type ¬¬[T] = ¬[¬[T]]
  type ∧[T, U] = T with U
  type ∨[T, U] = ¬[¬[T] ∧ ¬[U]]

  // Type-lambda for context bound
  type |∨|[T, U] = {
    type λ[X] = ¬¬[X] <:< (T ∨ U)
  }

  // Type inequalities
  trait =:!=[A, B]

  implicit def neq[A, B]: A =:!= B = new =:!=[A, B] {}
  implicit def neqAmbig1[A]: A =:!= A = unexpected
  implicit def neqAmbig2[A]: A =:!= A = unexpected

  trait <:!<[A, B]

  implicit def nsub[A, B]: A <:!< B = new <:!<[A, B] {}
  implicit def nsubAmbig1[A, B >: A]: A <:!< B = unexpected
  implicit def nsubAmbig2[A, B >: A]: A <:!< B = unexpected

  // Type-lambda for context bound
  type |¬|[T] = {
    type λ[U] = U <:!< T
  }

  // Quantifiers
  type ∃[P[_]] = P[T] forSome { type T }
  type ∀[P[_]] = ¬[∃[({ type λ[X] = ¬[P[X]] })#λ]]

  /** `Lens` definitions */
  val lens = LensDefns

  /** `Nat` literals */
  val nat = Nat

  /** `Poly` definitions */
  val poly = PolyDefns
  import poly._

  /** Dependent nullary function type. */
  trait DepFn0 {
    type Out
    def apply(): Out
  }

  /** Dependent unary function type. */
  trait DepFn1[T] {
    type Out
    def apply(t: T): Out
  }

  /** Dependent binary function type. */
  trait DepFn2[T, U] {
    type Out
    def apply(t: T, u: U): Out
  }

  /** The SYB everything combinator */
  type Everything[F <: Poly, K <: Poly, T] = Case1[EverythingAux[F, K], T]

  class ApplyEverything[F <: Poly] {
    def apply(k: Poly): EverythingAux[F, k.type] {} = new EverythingAux[F, k.type]
  }

  def everything(f: Poly): ApplyEverything[f.type] {} = new ApplyEverything[f.type]

  /** The SYB everywhere combinator */
  type Everywhere[F <: Poly, T] = Case1[EverywhereAux[F], T]

  def everywhere(f: Poly): EverywhereAux[f.type] {} = new EverywhereAux[f.type]
}
