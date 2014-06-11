
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

package ops

import function.FnFromProduct

trait FnFromProductInstances {
  type Aux[F, Out0] = FnFromProduct[F] { type Out = Out0 }

  implicit def fnFromProduct0[Res]: Aux[(HNil) ⇒ Res, () ⇒ Res] =
    new FnFromProduct[(HNil) ⇒ Res] {
      type Out = () ⇒ Res
      def apply(hf: (HNil) ⇒ Res): Out = () ⇒ hf(HNil)
    }

  implicit def fnFromProduct1[A, Res]: Aux[(A :: HNil) ⇒ Res, (A) ⇒ Res] =
    new FnFromProduct[(A :: HNil) ⇒ Res] {
      type Out = (A) ⇒ Res
      def apply(hf: (A :: HNil) ⇒ Res): Out = (a: A) ⇒ hf(a :: HNil)
    }

  implicit def fnFromProduct2[A, B, Res]: Aux[(A :: B :: HNil) ⇒ Res, (A, B) ⇒ Res] =
    new FnFromProduct[(A :: B :: HNil) ⇒ Res] {
      type Out = (A, B) ⇒ Res
      def apply(hf: (A :: B :: HNil) ⇒ Res): Out = (a: A, b: B) ⇒ hf(a :: b :: HNil)
    }

  implicit def fnFromProduct3[A, B, C, Res]: Aux[(A :: B :: C :: HNil) ⇒ Res, (A, B, C) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: HNil) ⇒ Res] {
      type Out = (A, B, C) ⇒ Res
      def apply(hf: (A :: B :: C :: HNil) ⇒ Res): Out = (a: A, b: B, c: C) ⇒ hf(a :: b :: c :: HNil)
    }

  implicit def fnFromProduct4[A, B, C, D, Res]: Aux[(A :: B :: C :: D :: HNil) ⇒ Res, (A, B, C, D) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: HNil) ⇒ Res] {
      type Out = (A, B, C, D) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D) ⇒ hf(a :: b :: c :: d :: HNil)
    }

  implicit def fnFromProduct5[A, B, C, D, E, Res]: Aux[(A :: B :: C :: D :: E :: HNil) ⇒ Res, (A, B, C, D, E) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E) ⇒ hf(a :: b :: c :: d :: e :: HNil)
    }

  implicit def fnFromProduct6[A, B, C, D, E, F, Res]: Aux[(A :: B :: C :: D :: E :: F :: HNil) ⇒ Res, (A, B, C, D, E, F) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F) ⇒ hf(a :: b :: c :: d :: e :: f :: HNil)
    }

  implicit def fnFromProduct7[A, B, C, D, E, F, G, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: HNil) ⇒ Res, (A, B, C, D, E, F, G) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: HNil)
    }

  implicit def fnFromProduct8[A, B, C, D, E, F, G, H, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: HNil)
    }

  implicit def fnFromProduct9[A, B, C, D, E, F, G, H, I, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil)
    }

  implicit def fnFromProduct10[A, B, C, D, E, F, G, H, I, J, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil)
    }

  implicit def fnFromProduct11[A, B, C, D, E, F, G, H, I, J, K, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil)
    }

  implicit def fnFromProduct12[A, B, C, D, E, F, G, H, I, J, K, L, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil)
    }

  implicit def fnFromProduct13[A, B, C, D, E, F, G, H, I, J, K, L, M, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil)
    }

  implicit def fnFromProduct14[A, B, C, D, E, F, G, H, I, J, K, L, M, N, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil)
    }

  implicit def fnFromProduct15[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil)
    }

  implicit def fnFromProduct16[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil)
    }

  implicit def fnFromProduct17[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil)
    }

  implicit def fnFromProduct18[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil)
    }

  implicit def fnFromProduct19[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil)
    }

  implicit def fnFromProduct20[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil)
    }

  implicit def fnFromProduct21[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil)
    }

  implicit def fnFromProduct22[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Res]: Aux[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil) ⇒ Res, (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ Res] =
    new FnFromProduct[(A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil) ⇒ Res] {
      type Out = (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ Res
      def apply(hf: (A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil) ⇒ Res): Out = (a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U, v: V) ⇒ hf(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil)
    }

}