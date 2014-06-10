
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

trait PolyApply {
  import poly._
  def apply[A](a: A)(implicit cse: Case[this.type, A :: HNil]): cse.Result =
    cse(a :: HNil)

  def apply[A, B](a: A, b: B)(implicit cse: Case[this.type, A :: B :: HNil]): cse.Result =
    cse(a :: b :: HNil)

  def apply[A, B, C](a: A, b: B, c: C)(implicit cse: Case[this.type, A :: B :: C :: HNil]): cse.Result =
    cse(a :: b :: c :: HNil)

  def apply[A, B, C, D](a: A, b: B, c: C, d: D)(implicit cse: Case[this.type, A :: B :: C :: D :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: HNil)

  def apply[A, B, C, D, E](a: A, b: B, c: C, d: D, e: E)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: HNil)

  def apply[A, B, C, D, E, F](a: A, b: B, c: C, d: D, e: E, f: F)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: HNil)

  def apply[A, B, C, D, E, F, G](a: A, b: B, c: C, d: D, e: E, f: F, g: G)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: HNil)

  def apply[A, B, C, D, E, F, G, H](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: HNil)

  def apply[A, B, C, D, E, F, G, H, I](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil)

  def apply[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I, j: J, k: K, l: L, m: M, n: N, o: O, p: P, q: Q, r: R, s: S, t: T, u: U, v: V)(implicit cse: Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil]): cse.Result =
    cse(a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil)

}