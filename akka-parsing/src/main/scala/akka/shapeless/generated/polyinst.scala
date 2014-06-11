
/*
 * Copyright (c) 2011-14 Miles Sabin
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

trait PolyInst {

  implicit def inst1[A](fn: Poly)(implicit cse: fn.ProductCase[A :: HNil]): (A) ⇒ cse.Result =
    (a: A) ⇒ cse(a :: HNil)

  implicit def inst2[A, B](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: HNil]): (A, B) ⇒ cse.Result =
    (a: A, b: B) ⇒ cse(a :: b :: HNil)

  implicit def inst3[A, B, C](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: HNil]): (A, B, C) ⇒ cse.Result =
    (a: A, b: B, c: C) ⇒ cse(a :: b :: c :: HNil)

  implicit def inst4[A, B, C, D](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: HNil]): (A, B, C, D) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D) ⇒ cse(a :: b :: c :: d :: HNil)

  implicit def inst5[A, B, C, D, E](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: HNil]): (A, B, C, D, E) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E) ⇒ cse(a :: b :: c :: d :: e :: HNil)

  implicit def inst6[A, B, C, D, E, F](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: HNil]): (A, B, C, D, E, F) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F) ⇒ cse(a :: b :: c :: d :: e :: f :: HNil)

  implicit def inst7[A, B, C, D, E, F, G](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: HNil]): (A, B, C, D, E, F, G) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: HNil)

  implicit def inst8[A, B, C, D, E, F, G, H](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: HNil]): (A, B, C, D, E, F, G, H) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: HNil)

  implicit def inst9[A, B, C, D, E, F, G, H, I](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil]): (A, B, C, D, E, F, G, H, I) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil)

  implicit def inst10[A, B, C, D, E, F, G, H, I, J](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil]): (A, B, C, D, E, F, G, H, I, J) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil)

  implicit def inst11[A, B, C, D, E, F, G, H, I, J, K](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil]): (A, B, C, D, E, F, G, H, I, J, K) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil)

  implicit def inst12[A, B, C, D, E, F, G, H, I, J, K, L](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil)

  implicit def inst13[A, B, C, D, E, F, G, H, I, J, K, L, M](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil)

  implicit def inst14[A, B, C, D, E, F, G, H, I, J, K, L, M, N](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil)

  implicit def inst15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil)

  implicit def inst16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil)

  implicit def inst17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil)

  implicit def inst18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil)

  implicit def inst19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil)

  implicit def inst20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil)

  implicit def inst21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil)

  implicit def inst22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](fn: Poly)(implicit cse: fn.ProductCase[A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil]): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ cse.Result =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U, v: V) ⇒ cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil)

}