
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

trait Poly1 extends Poly { outer ⇒
  type Case[A] = poly.Case[this.type, A :: HNil]

  object Case {
    type Aux[A, Result0] = poly.Case[outer.type, A :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A] {
    def apply[Res](fn: (A) ⇒ Res) = new Case[A] {
      type Result = Res
      val value = (l: A :: HNil) ⇒ l match { case a :: HNil ⇒ fn(a) }
    }
  }

  def at[A] = new CaseBuilder[A]
}

trait Poly2 extends Poly { outer ⇒
  type Case[A, B] = poly.Case[this.type, A :: B :: HNil]

  object Case {
    type Aux[A, B, Result0] = poly.Case[outer.type, A :: B :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B] {
    def apply[Res](fn: (A, B) ⇒ Res) = new Case[A, B] {
      type Result = Res
      val value = (l: A :: B :: HNil) ⇒ l match { case a :: b :: HNil ⇒ fn(a, b) }
    }
  }

  def at[A, B] = new CaseBuilder[A, B]
}

trait Poly3 extends Poly { outer ⇒
  type Case[A, B, C] = poly.Case[this.type, A :: B :: C :: HNil]

  object Case {
    type Aux[A, B, C, Result0] = poly.Case[outer.type, A :: B :: C :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C] {
    def apply[Res](fn: (A, B, C) ⇒ Res) = new Case[A, B, C] {
      type Result = Res
      val value = (l: A :: B :: C :: HNil) ⇒ l match { case a :: b :: c :: HNil ⇒ fn(a, b, c) }
    }
  }

  def at[A, B, C] = new CaseBuilder[A, B, C]
}

trait Poly4 extends Poly { outer ⇒
  type Case[A, B, C, D] = poly.Case[this.type, A :: B :: C :: D :: HNil]

  object Case {
    type Aux[A, B, C, D, Result0] = poly.Case[outer.type, A :: B :: C :: D :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D] {
    def apply[Res](fn: (A, B, C, D) ⇒ Res) = new Case[A, B, C, D] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: HNil) ⇒ l match { case a :: b :: c :: d :: HNil ⇒ fn(a, b, c, d) }
    }
  }

  def at[A, B, C, D] = new CaseBuilder[A, B, C, D]
}

trait Poly5 extends Poly { outer ⇒
  type Case[A, B, C, D, E] = poly.Case[this.type, A :: B :: C :: D :: E :: HNil]

  object Case {
    type Aux[A, B, C, D, E, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E] {
    def apply[Res](fn: (A, B, C, D, E) ⇒ Res) = new Case[A, B, C, D, E] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: HNil ⇒ fn(a, b, c, d, e) }
    }
  }

  def at[A, B, C, D, E] = new CaseBuilder[A, B, C, D, E]
}

trait Poly6 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F] {
    def apply[Res](fn: (A, B, C, D, E, F) ⇒ Res) = new Case[A, B, C, D, E, F] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: HNil ⇒ fn(a, b, c, d, e, f) }
    }
  }

  def at[A, B, C, D, E, F] = new CaseBuilder[A, B, C, D, E, F]
}

trait Poly7 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G] {
    def apply[Res](fn: (A, B, C, D, E, F, G) ⇒ Res) = new Case[A, B, C, D, E, F, G] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: HNil ⇒ fn(a, b, c, d, e, f, g) }
    }
  }

  def at[A, B, C, D, E, F, G] = new CaseBuilder[A, B, C, D, E, F, G]
}

trait Poly8 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H) ⇒ Res) = new Case[A, B, C, D, E, F, G, H] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: HNil ⇒ fn(a, b, c, d, e, f, g, h) }
    }
  }

  def at[A, B, C, D, E, F, G, H] = new CaseBuilder[A, B, C, D, E, F, G, H]
}

trait Poly9 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I] = new CaseBuilder[A, B, C, D, E, F, G, H, I]
}

trait Poly10 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J]
}

trait Poly11 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K]
}

trait Poly12 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L]
}

trait Poly13 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M]
}

trait Poly14 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N]
}

trait Poly15 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O]
}

trait Poly16 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P]
}

trait Poly17 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q]
}

trait Poly18 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R]
}

trait Poly19 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S]
}

trait Poly20 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]
}

trait Poly21 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]
}

trait Poly22 extends Poly { outer ⇒
  type Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] = poly.Case[this.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil]

  object Case {
    type Aux[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, Result0] = poly.Case[outer.type, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil] { type Result = Result0 }
  }

  class CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] {
    def apply[Res](fn: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ Res) = new Case[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] {
      type Result = Res
      val value = (l: A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil) ⇒ l match { case a :: b :: c :: d :: e :: f :: g :: h :: i :: j :: k :: l :: m :: n :: o :: p :: q :: r :: s :: t :: u :: v :: HNil ⇒ fn(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v) }
    }
  }

  def at[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] = new CaseBuilder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]
}
