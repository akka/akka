/*
 * Copyright (C) 2009-2016 Mathias Doenitz, Alexander Myltsev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.parboiled2.support

import akka.shapeless._
import akka.parboiled2.Rule

// format: OFF

// provides the supported `~>` "overloads" for rules of type `Rule[I, O]` as `Out`
// as a phantom type, which is only used for rule DSL typing

sealed trait ActionOps[I <: HList, O <: HList] { type Out }
object ActionOps {
  private type SJoin[I <: HList, O <: HList, R] = Join[I, HNil, O, R]

  implicit def ops0[II <: HList, OO <: HNil]: ActionOps[II, OO] { type Out = Ops0[II] } = `n/a`
  sealed trait Ops0[II <: HList] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR)
        (implicit j: SJoin[E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z) ⇒ RR]): Rule[j.In, j.Out]
       
  }
    
  implicit def ops1[II <: HList, A]: ActionOps[II, A :: HNil] { type Out = Ops1[II, A] } = `n/a`
  sealed trait Ops1[II <: HList, A] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]


    def apply[RR](f: (A) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR)
        (implicit j: SJoin[F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops2[II <: HList, A, B]: ActionOps[II, A :: B :: HNil] { type Out = Ops2[II, A, B] } = `n/a`
  sealed trait Ops2[II <: HList, A, B] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (B) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR)
        (implicit j: SJoin[G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops3[II <: HList, A, B, C]: ActionOps[II, A :: B :: C :: HNil] { type Out = Ops3[II, A, B, C] } = `n/a`
  sealed trait Ops3[II <: HList, A, B, C] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (C) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR)
        (implicit j: SJoin[H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops4[II <: HList, A, B, C, D]: ActionOps[II, A :: B :: C :: D :: HNil] { type Out = Ops4[II, A, B, C, D] } = `n/a`
  sealed trait Ops4[II <: HList, A, B, C, D] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (D) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR)
        (implicit j: SJoin[I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops5[II <: HList, A, B, C, D, E]: ActionOps[II, A :: B :: C :: D :: E :: HNil] { type Out = Ops5[II, A, B, C, D, E] } = `n/a`
  sealed trait Ops5[II <: HList, A, B, C, D, E] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (E) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR)
        (implicit j: SJoin[J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops6[II <: HList, A, B, C, D, E, F]: ActionOps[II, A :: B :: C :: D :: E :: F :: HNil] { type Out = Ops6[II, A, B, C, D, E, F] } = `n/a`
  sealed trait Ops6[II <: HList, A, B, C, D, E, F] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (F) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR)
        (implicit j: SJoin[K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops7[II <: HList, A, B, C, D, E, F, G]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: HNil] { type Out = Ops7[II, A, B, C, D, E, F, G] } = `n/a`
  sealed trait Ops7[II <: HList, A, B, C, D, E, F, G] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (G) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR)
        (implicit j: SJoin[L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops8[II <: HList, A, B, C, D, E, F, G, H]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil] { type Out = Ops8[II, A, B, C, D, E, F, G, H] } = `n/a`
  sealed trait Ops8[II <: HList, A, B, C, D, E, F, G, H] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR)
        (implicit j: SJoin[M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops9[II <: HList, A, B, C, D, E, F, G, H, I]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil] { type Out = Ops9[II, A, B, C, D, E, F, G, H, I] } = `n/a`
  sealed trait Ops9[II <: HList, A, B, C, D, E, F, G, H, I] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[N, O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR)
        (implicit j: SJoin[N :: O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops10[II <: HList, A, B, C, D, E, F, G, H, I, J]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil] { type Out = Ops10[II, A, B, C, D, E, F, G, H, I, J] } = `n/a`
  sealed trait Ops10[II <: HList, A, B, C, D, E, F, G, H, I, J] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[O, P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR)
        (implicit j: SJoin[O :: P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops11[II <: HList, A, B, C, D, E, F, G, H, I, J, K]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil] { type Out = Ops11[II, A, B, C, D, E, F, G, H, I, J, K] } = `n/a`
  sealed trait Ops11[II <: HList, A, B, C, D, E, F, G, H, I, J, K] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[P, Q, R, S, T, U, V, W, X, Y, Z, RR](f: (P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR)
        (implicit j: SJoin[P :: Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops12[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil] { type Out = Ops12[II, A, B, C, D, E, F, G, H, I, J, K, L] } = `n/a`
  sealed trait Ops12[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Q, R, S, T, U, V, W, X, Y, Z, RR](f: (Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR)
        (implicit j: SJoin[Q :: R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops13[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil] { type Out = Ops13[II, A, B, C, D, E, F, G, H, I, J, K, L, M] } = `n/a`
  sealed trait Ops13[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[R, S, T, U, V, W, X, Y, Z, RR](f: (R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR)
        (implicit j: SJoin[R :: S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(R, S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops14[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil] { type Out = Ops14[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N] } = `n/a`
  sealed trait Ops14[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[S, T, U, V, W, X, Y, Z, RR](f: (S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR)
        (implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(S, T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops15[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil] { type Out = Ops15[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] } = `n/a`
  sealed trait Ops15[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[T, U, V, W, X, Y, Z, RR](f: (T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR)
        (implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(T, U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops16[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil] { type Out = Ops16[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] } = `n/a`
  sealed trait Ops16[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[U, V, W, X, Y, Z, RR](f: (U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR)
        (implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(U, V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops17[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil] { type Out = Ops17[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] } = `n/a`
  sealed trait Ops17[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[V, W, X, Y, Z, RR](f: (V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR)
        (implicit j: SJoin[V :: W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(V, W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops18[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil] { type Out = Ops18[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] } = `n/a`
  sealed trait Ops18[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR],
                  c: FCapture[(R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[W, X, Y, Z, RR](f: (W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR)
        (implicit j: SJoin[W :: X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(W, X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops19[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil] { type Out = Ops19[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] } = `n/a`
  sealed trait Ops19[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil, RR],
                  c: FCapture[(S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR],
                  c: FCapture[(R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[X, Y, Z, RR](f: (X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR)
        (implicit j: SJoin[X :: Y :: Z :: II, HNil, RR],
                  c: FCapture[(X, Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops20[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil] { type Out = Ops20[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] } = `n/a`
  sealed trait Ops20[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil, RR],
                  c: FCapture[(T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil, RR],
                  c: FCapture[(S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR],
                  c: FCapture[(R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[Y, Z, RR](f: (Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR)
        (implicit j: SJoin[Y :: Z :: II, HNil, RR],
                  c: FCapture[(Y, Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops21[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil] { type Out = Ops21[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] } = `n/a`
  sealed trait Ops21[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil, RR],
                  c: FCapture[(U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil, RR],
                  c: FCapture[(T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil, RR],
                  c: FCapture[(S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR],
                  c: FCapture[(R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]

    def apply[Z, RR](f: (Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR)
        (implicit j: SJoin[Z :: II, HNil, RR],
                  c: FCapture[(Z, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U) ⇒ RR]): Rule[j.In, j.Out]
       
  }
     

  implicit def ops22[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]: ActionOps[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil] { type Out = Ops22[II, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] } = `n/a`
  sealed trait Ops22[II <: HList, A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V] {
    def apply[RR](f: () ⇒ RR)(implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: V :: HNil, RR], c: FCapture[() ⇒ RR]): Rule[j.In, j.Out]

    def apply[RR](f: (V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: U :: HNil, RR],
                  c: FCapture[(V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: T :: HNil, RR],
                  c: FCapture[(U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: S :: HNil, RR],
                  c: FCapture[(T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: R :: HNil, RR],
                  c: FCapture[(S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: Q :: HNil, RR],
                  c: FCapture[(R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: P :: HNil, RR],
                  c: FCapture[(Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: O :: HNil, RR],
                  c: FCapture[(P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: N :: HNil, RR],
                  c: FCapture[(O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: M :: HNil, RR],
                  c: FCapture[(N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: L :: HNil, RR],
                  c: FCapture[(M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: K :: HNil, RR],
                  c: FCapture[(L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: J :: HNil, RR],
                  c: FCapture[(K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, RR],
                  c: FCapture[(J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: H :: HNil, RR],
                  c: FCapture[(I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: G :: HNil, RR],
                  c: FCapture[(H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: F :: HNil, RR],
                  c: FCapture[(G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: E :: HNil, RR],
                  c: FCapture[(F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: D :: HNil, RR],
                  c: FCapture[(E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: C :: HNil, RR],
                  c: FCapture[(D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: B :: HNil, RR],
                  c: FCapture[(C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)
        (implicit j: SJoin[II, A :: HNil, RR],
                  c: FCapture[(B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]
       
    def apply[RR](f: (A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR)(implicit j: SJoin[II, HNil, RR], c: FCapture[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V) ⇒ RR]): Rule[j.In, j.Out]


  }
     }