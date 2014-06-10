
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

trait CaseInst {
  import poly._

  implicit def inst1[Fn <: Poly, A, Res](cse: Case[Fn, A :: HNil] { type Result = Res }): (A) ⇒ Res =
    (a: A) ⇒ cse.value(a :: HNil)

  implicit def inst2[Fn <: Poly, A, B, Res](cse: Case[Fn, A :: B :: HNil] { type Result = Res }): (A, B) ⇒ Res =
    (a: A, b: B) ⇒ cse.value(a :: b :: HNil)

  implicit def inst3[Fn <: Poly, A, B, C, Res](cse: Case[Fn, A :: B :: C :: HNil] { type Result = Res }): (A, B, C) ⇒ Res =
    (a: A, b: B, c: C) ⇒ cse.value(a :: b :: c :: HNil)

  implicit def inst4[Fn <: Poly, A, B, C, D, Res](cse: Case[Fn, A :: B :: C :: D :: HNil] { type Result = Res }): (A, B, C, D) ⇒ Res =
    (a: A, b: B, c: C, d: D) ⇒ cse.value(a :: b :: c :: d :: HNil)

  implicit def inst5[Fn <: Poly, A, B, C, D, E, Res](cse: Case[Fn, A :: B :: C :: D :: E :: HNil] { type Result = Res }): (A, B, C, D, E) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E) ⇒ cse.value(a :: b :: c :: d :: e :: HNil)

  implicit def inst6[Fn <: Poly, A, B, C, D, E, F, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: HNil] { type Result = Res }): (A, B, C, D, E, F) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F) ⇒ cse.value(a :: b :: c :: d :: e :: f :: HNil)

  implicit def inst7[Fn <: Poly, A, B, C, D, E, F, G, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: HNil] { type Result = Res }): (A, B, C, D, E, F, G) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: HNil)

  implicit def inst8[Fn <: Poly, A, B, C, D, E, F, G, H, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: HNil)

  implicit def inst9[Fn <: Poly, A, B, C, D, E, F, G, H, I, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil)

  implicit def inst10[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil)

  implicit def inst11[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil)

  implicit def inst12[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil)

  implicit def inst13[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil)

  implicit def inst14[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil)

  implicit def inst15[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil)

  implicit def inst16[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil)

  implicit def inst17[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil)

  implicit def inst18[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil)

  implicit def inst19[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil)

  implicit def inst20[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil)

  implicit def inst21[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil)

  implicit def inst22[Fn <: Poly, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Res](cse: Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil] { type Result = Res }): (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ Res =
    (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U, v: V) ⇒ cse.value(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil)

}