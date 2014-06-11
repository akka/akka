
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

trait Cases {
  import poly._

  type Case1[Fn, A] = Case[Fn, A :: HNil]

  object Case1 {
    type Aux[Fn, A, Result0] = Case[Fn, A :: HNil] { type Result = Result0 }

    def apply[Fn, A, Result0](fn: (A) ⇒ Result0): Aux[Fn, A, Result0] =
      new Case[Fn, A :: HNil] {
        type Result = Result0
        val value = (l: A :: HNil) ⇒ l match {
          case a :: HNil ⇒
            fn(a)
        }
      }
  }

  type Case2[Fn, A, B] = Case[Fn, A :: B :: HNil]

  object Case2 {
    type Aux[Fn, A, B, Result0] = Case[Fn, A :: B :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, Result0](fn: (A, B) ⇒ Result0): Aux[Fn, A, B, Result0] =
      new Case[Fn, A :: B :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: HNil) ⇒ l match {
          case a :: b :: HNil ⇒
            fn(a, b)
        }
      }
  }

  type Case3[Fn, A, B, C] = Case[Fn, A :: B :: C :: HNil]

  object Case3 {
    type Aux[Fn, A, B, C, Result0] = Case[Fn, A :: B :: C :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, Result0](fn: (A, B, C) ⇒ Result0): Aux[Fn, A, B, C, Result0] =
      new Case[Fn, A :: B :: C :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: HNil) ⇒ l match {
          case a :: b :: c :: HNil ⇒
            fn(a, b, c)
        }
      }
  }

  type Case4[Fn, A, B, C, D] = Case[Fn, A :: B :: C :: D :: HNil]

  object Case4 {
    type Aux[Fn, A, B, C, D, Result0] = Case[Fn, A :: B :: C :: D :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, Result0](fn: (A, B, C, D) ⇒ Result0): Aux[Fn, A, B, C, D, Result0] =
      new Case[Fn, A :: B :: C :: D :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: HNil) ⇒ l match {
          case a :: b :: c :: d :: HNil ⇒
            fn(a, b, c, d)
        }
      }
  }

  type Case5[Fn, A, B, C, D, E] = Case[Fn, A :: B :: C :: D :: E :: HNil]

  object Case5 {
    type Aux[Fn, A, B, C, D, E, Result0] = Case[Fn, A :: B :: C :: D :: E :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, Result0](fn: (A, B, C, D, E) ⇒ Result0): Aux[Fn, A, B, C, D, E, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: HNil ⇒
            fn(a, b, c, d, e)
        }
      }
  }

  type Case6[Fn, A, B, C, D, E, F] = Case[Fn, A :: B :: C :: D :: E :: F :: HNil]

  object Case6 {
    type Aux[Fn, A, B, C, D, E, F, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, Result0](fn: (A, B, C, D, E, F) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: HNil ⇒
            fn(a, b, c, d, e, f)
        }
      }
  }

  type Case7[Fn, A, B, C, D, E, F, G] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: HNil]

  object Case7 {
    type Aux[Fn, A, B, C, D, E, F, G, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, Result0](fn: (A, B, C, D, E, F, G) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: HNil ⇒
            fn(a, b, c, d, e, f, g)
        }
      }
  }

  type Case8[Fn, A, B, C, D, E, F, G, H] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: HNil]

  object Case8 {
    type Aux[Fn, A, B, C, D, E, F, G, H, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, Result0](fn: (A, B, C, D, E, F, G, H) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: HNil ⇒
            fn(a, b, c, d, e, f, g, h)
        }
      }
  }

  type Case9[Fn, A, B, C, D, E, F, G, H, I] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil]

  object Case9 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, Result0](fn: (A, B, C, D, E, F, G, H, I) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i)
        }
      }
  }

  type Case10[Fn, A, B, C, D, E, F, G, H, I, J] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil]

  object Case10 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, Result0](fn: (A, B, C, D, E, F, G, H, I, J) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j)
        }
      }
  }

  type Case11[Fn, A, B, C, D, E, F, G, H, I, J, K] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil]

  object Case11 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k)
        }
      }
  }

  type Case12[Fn, A, B, C, D, E, F, G, H, I, J, K, L] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil]

  object Case12 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l)
        }
      }
  }

  type Case13[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil]

  object Case13 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m)
        }
      }
  }

  type Case14[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil]

  object Case14 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n)
        }
      }
  }

  type Case15[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil]

  object Case15 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)
        }
      }
  }

  type Case16[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil]

  object Case16 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)
        }
      }
  }

  type Case17[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil]

  object Case17 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)
        }
      }
  }

  type Case18[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil]

  object Case18 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r)
        }
      }
  }

  type Case19[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil]

  object Case19 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)
        }
      }
  }

  type Case20[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil]

  object Case20 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)
        }
      }
  }

  type Case21[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil]

  object Case21 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)
        }
      }
  }

  type Case22[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil]

  object Case22 {
    type Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Result0] = Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil] { type Result = Result0 }

    def apply[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Result0](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ Result0): Aux[Fn, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Result0] =
      new Case[Fn, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil] {
        type Result = Result0
        val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil) ⇒ l match {
          case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil ⇒
            fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)
        }
      }
  }

}