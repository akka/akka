/*
 * Copyright (C) 2009-2013 Mathias Doenitz, Alexander Myltsev
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
import akka.shapeless.ops.hlist.ReversePrepend

// format: OFF

// provides the supported `~>` "overloads" for rule of type `Rule[I, O]` as `Out`
// as a phantom type, which is only used for rule DSL typing
sealed trait ActionOps[I <: HList, O <: HList] { type Out }
object ActionOps {
  private type SJoin[I <: HList, O <: HList, R] = Join[I, HNil, O, R]

  implicit def ops0[I <: HList, O <: HNil]: ActionOps[I, O] { type Out = Ops0[I] } = `n/a`
  sealed trait Ops0[I <: HList] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: Z ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[Z ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[V, W, X, Y, Z, R](f: (V, W, X, Y, Z) ⇒ R)(implicit j: SJoin[V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(V, W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[U, V, W, X, Y, Z, R](f: (U, V, W, X, Y, Z) ⇒ R)(implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(U, V, W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[T, U, V, W, X, Y, Z, R](f: (T, U, V, W, X, Y, Z) ⇒ R)(implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(T, U, V, W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[S, T, U, V, W, X, Y, Z, R](f: (S, T, U, V, W, X, Y, Z) ⇒ R)(implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(S, T, U, V, W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
    def apply[P, S, T, U, V, W, X, Y, Z, R](f: (P, S, T, U, V, W, X, Y, Z) ⇒ R)(implicit j: SJoin[P :: S :: T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(P, S, T, U, V, W, X, Y, Z) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops1[I <: HList, A]: ActionOps[I, A :: HNil] { type Out = Ops1[I, A] } = `n/a`
  sealed trait Ops1[I <: HList, A] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: A ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[A ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z, A) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[V, W, X, Y, Z, R](f: (V, W, X, Y, Z, A) ⇒ R)(implicit j: SJoin[V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(V, W, X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[U, V, W, X, Y, Z, R](f: (U, V, W, X, Y, Z, A) ⇒ R)(implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(U, V, W, X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[T, U, V, W, X, Y, Z, R](f: (T, U, V, W, X, Y, Z, A) ⇒ R)(implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(T, U, V, W, X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
    def apply[S, T, U, V, W, X, Y, Z, R](f: (S, T, U, V, W, X, Y, Z, A) ⇒ R)(implicit j: SJoin[S :: T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(S, T, U, V, W, X, Y, Z, A) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops2[I <: HList, A, B]: ActionOps[I, A :: B :: HNil] { type Out = Ops2[I, A, B] } = `n/a`
  sealed trait Ops2[I <: HList, A, B] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: B ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[B ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A, B) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z, A, B) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[V, W, X, Y, Z, R](f: (V, W, X, Y, Z, A, B) ⇒ R)(implicit j: SJoin[V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(V, W, X, Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[U, V, W, X, Y, Z, R](f: (U, V, W, X, Y, Z, A, B) ⇒ R)(implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(U, V, W, X, Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
    def apply[T, U, V, W, X, Y, Z, R](f: (T, U, V, W, X, Y, Z, A, B) ⇒ R)(implicit j: SJoin[T :: U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(T, U, V, W, X, Y, Z, A, B) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops3[I <: HList, A, B, C]: ActionOps[I, A :: B :: C :: HNil] { type Out = Ops3[I, A, B, C] } = `n/a`
  sealed trait Ops3[I <: HList, A, B, C] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: C ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[C ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B, C) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A, B, C) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z, A, B, C) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[V, W, X, Y, Z, R](f: (V, W, X, Y, Z, A, B, C) ⇒ R)(implicit j: SJoin[V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(V, W, X, Y, Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
    def apply[U, V, W, X, Y, Z, R](f: (U, V, W, X, Y, Z, A, B, C) ⇒ R)(implicit j: SJoin[U :: V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(U, V, W, X, Y, Z, A, B, C) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops4[I <: HList, A, B, C, D]: ActionOps[I, A :: B :: C :: D :: HNil] { type Out = Ops4[I, A, B, C, D] } = `n/a`
  sealed trait Ops4[I <: HList, A, B, C, D] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: D ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[D ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D) ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[(C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C, D) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B, C, D) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A, B, C, D) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z, A, B, C, D) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z, A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
    def apply[V, W, X, Y, Z, R](f: (V, W, X, Y, Z, A, B, C, D) ⇒ R)(implicit j: SJoin[V :: W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(V, W, X, Y, Z, A, B, C, D) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops5[I <: HList, A, B, C, D, E]: ActionOps[I, A :: B :: C :: D :: E :: HNil] { type Out = Ops5[I, A, B, C, D, E] } = `n/a`
  sealed trait Ops5[I <: HList, A, B, C, D, E] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: E ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: HNil, R], c: FCapture[E ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (D, E) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[(D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D, E) ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[(C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D, E) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D, E) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C, D, E) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B, C, D, E) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A, B, C, D, E) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A, B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
    def apply[W, X, Y, Z, R](f: (W, X, Y, Z, A, B, C, D, E) ⇒ R)(implicit j: SJoin[W :: X :: Y :: Z :: I, HNil, R], c: FCapture[(W, X, Y, Z, A, B, C, D, E) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops6[I <: HList, A, B, C, D, E, F]: ActionOps[I, A :: B :: C :: D :: E :: F :: HNil] { type Out = Ops6[I, A, B, C, D, E, F] } = `n/a`
  sealed trait Ops6[I <: HList, A, B, C, D, E, F] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: F ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: HNil, R], c: FCapture[F ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (E, F) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: HNil, R], c: FCapture[(E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (D, E, F) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[(D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D, E, F) ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[(C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D, E, F) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D, E, F) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C, D, E, F) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B, C, D, E, F) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B, C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
    def apply[X, Y, Z, R](f: (X, Y, Z, A, B, C, D, E, F) ⇒ R)(implicit j: SJoin[X :: Y :: Z :: I, HNil, R], c: FCapture[(X, Y, Z, A, B, C, D, E, F) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops7[I <: HList, A, B, C, D, E, F, G]: ActionOps[I, A :: B :: C :: D :: E :: F :: G :: HNil] { type Out = Ops7[I, A, B, C, D, E, F, G] } = `n/a`
  sealed trait Ops7[I <: HList, A, B, C, D, E, F, G] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: G :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: G ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: HNil, R], c: FCapture[G ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (F, G) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: HNil, R], c: FCapture[(F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (E, F, G) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: HNil, R], c: FCapture[(E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (D, E, F, G) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[(D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D, E, F, G) ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[(C, D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D, E, F, G) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C, D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D, E, F, G) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C, D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C, D, E, F, G) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C, D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
    def apply[Y, Z, R](f: (Y, Z, A, B, C, D, E, F, G) ⇒ R)(implicit j: SJoin[Y :: Z :: I, HNil, R], c: FCapture[(Y, Z, A, B, C, D, E, F, G) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops8[I <: HList, A, B, C, D, E, F, G, H]: ActionOps[I, A :: B :: C :: D :: E :: F :: G :: H :: HNil] { type Out = Ops8[I, A, B, C, D, E, F, G, H] } = `n/a`
  sealed trait Ops8[I <: HList, A, B, C, D, E, F, G, H] {
    def apply[R](f: () ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: G :: H :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: H ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: G :: HNil, R], c: FCapture[H ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (G, H) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: F :: HNil, R], c: FCapture[(G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (F, G, H) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: E :: HNil, R], c: FCapture[(F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (E, F, G, H) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: D :: HNil, R], c: FCapture[(E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (D, E, F, G, H) ⇒ R)(implicit j: SJoin[I, A :: B :: C :: HNil, R], c: FCapture[(D, E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D, E, F, G, H) ⇒ R)(implicit j: SJoin[I, A :: B :: HNil, R], c: FCapture[(C, D, E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D, E, F, G, H) ⇒ R)(implicit j: SJoin[I, A :: HNil, R], c: FCapture[(B, C, D, E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D, E, F, G, H) ⇒ R)(implicit j: SJoin[I, HNil, R], c: FCapture[(A, B, C, D, E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
    def apply[Z, R](f: (Z, A, B, C, D, E, F, G, H) ⇒ R)(implicit j: SJoin[Z :: I, HNil, R], c: FCapture[(Z, A, B, C, D, E, F, G, H) ⇒ R]): Rule[j.In, j.Out]
  }
  implicit def ops[I <: HList, O <: HList, OI <: HList, A, B, C, D, E, F, G, H, J]
    (implicit x: TakeRight9[O, OI, A, B, C, D, E, F, G, H, J]): ActionOps[I, O] { type Out = Ops[I, OI, A, B, C, D, E, F, G, H, J] } = `n/a`
  sealed trait Ops[I <: HList, OI <: HList, A, B, C, D, E, F, G, H, J] {
    def apply[R](f: () ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: E :: F :: G :: H :: J :: HNil, R], c: FCapture[() ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: J ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: E :: F :: G :: H :: HNil, R], c: FCapture[J ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: E :: F :: G :: HNil, R], c: FCapture[(H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: E :: F :: HNil, R], c: FCapture[(G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (F, G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: E :: HNil, R], c: FCapture[(F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (E, F, G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: D :: HNil, R], c: FCapture[(E, F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (D, E, F, G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: C :: HNil, R], c: FCapture[(D, E, F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (C, D, E, F, G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: B :: HNil, R], c: FCapture[(C, D, E, F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (B, C, D, E, F, G, H, J) ⇒ R)(implicit j: Join[I, OI, A :: HNil, R], c: FCapture[(B, C, D, E, F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
    def apply[R](f: (A, B, C, D, E, F, G, H, J) ⇒ R)(implicit j: Join[I, OI, HNil, R], c: FCapture[(A, B, C, D, E, F, G, H, J) ⇒ R]): Rule[j.In, j.Out]
  }
}

// we want to support the "short case class notation" `... ~> Foo`
// unfortunately the Tree for the function argument to the `apply` overloads above does *not* allow us to inspect the
// function type which is why we capture it separately with this helper type
sealed trait FCapture[T]
object FCapture {
  implicit def apply[T]: FCapture[T] = `n/a`
}

// builds `In` and `Out` types according to this logic:
//  if (R == Unit)
//    In = I, Out = L1 ::: L2
//  else if (R <: HList)
//    In = I, Out = L1 ::: L2 ::: R
//  else if (R <: Rule[I2, O2])
//    In = TailSwitch[I2, L1 ::: L2, I], Out = TailSwitch[L1 ::: L2, I2, O2]
//  else
//    In = I, Out = L1 ::: L2 ::: R :: HNil
sealed trait Join[I <: HList, L1 <: HList, L2 <: HList, R] {
  type In <: HList
  type Out <: HList
}
object Join {
  implicit def join[I <: HList, L1 <: HList, L2 <: HList, R, In0 <: HList, Out0 <: HList]
  (implicit x: Aux[I, L1, L2, R, HNil, In0, Out0]): Join[I, L1, L2, R] { type In = In0; type Out = Out0 } = `n/a`
  
  sealed trait Aux[I <: HList, L1 <: HList, L2 <: HList, R, Acc <: HList, In <: HList, Out <: HList]
  object Aux extends Aux1 {
    // if R == Unit convert to HNil
    implicit def forUnit[I <: HList, L1 <: HList, L2 <: HList, Acc <: HList, Out <: HList]
    (implicit x: Aux[I, L1, L2, HNil, Acc, I, Out]): Aux[I, L1, L2, Unit, Acc, I, Out] = `n/a`

    // if R <: HList and L1 non-empty move head of L1 to Acc
    implicit def iter1[I <: HList, H, T <: HList, L2 <: HList, R <: HList, Acc <: HList, Out <: HList]
    (implicit x: Aux[I, T, L2, R, H :: Acc, I, Out]): Aux[I, H :: T, L2, R, Acc, I, Out] = `n/a`

    // if R <: HList and L1 empty and L2 non-empty move head of L2 to Acc
    implicit def iter2[I <: HList, H, T <: HList, R <: HList, Acc <: HList, Out <: HList]
    (implicit x: Aux[I, HNil, T, R, H :: Acc, I, Out]): Aux[I, HNil, H :: T, R, Acc, I, Out] = `n/a`

    // if R <: HList and L1 and L2 empty set Out = reversePrepend Acc before R
    implicit def terminate[I <: HList, R <: HList, Acc <: HList, Out <: HList]
    (implicit x: ReversePrepend.Aux[Acc, R, Out]): Aux[I, HNil, HNil, R, Acc, I, Out] = `n/a`

    // if R <: Rule and L1 non-empty move head of L1 to Acc
    implicit def iterRule1[I <: HList, L2 <: HList, I2 <: HList, O2 <: HList, In0 <: HList, Acc <: HList, Out0 <: HList, H, T <: HList]
    (implicit x: Aux[I, T, L2, Rule[I2, O2], H :: Acc, In0, Out0]): Aux[I, H :: T, L2, Rule[I2, O2], HNil, In0, Out0] = `n/a`

    // if R <: Rule and L1 empty and Acc non-empty move head of Acc to L2
    implicit def iterRule2[I <: HList, L2 <: HList, I2 <: HList, O2 <: HList, In0 <: HList, Out0 <: HList, H, T <: HList]
    (implicit x: Aux[I, HNil, H :: L2, Rule[I2, O2], T, In0, Out0]): Aux[I, HNil, L2, Rule[I2, O2], H :: T, In0, Out0] = `n/a`

    // if R <: Rule and L1 and Acc empty set In and Out to tailswitches result
    implicit def terminateRule[I <: HList, O <: HList, I2 <: HList, O2 <: HList, In <: HList, Out <: HList]
    (implicit i: TailSwitch.Aux[I2, I2, O, O, I, HNil, In], o: TailSwitch.Aux[O, O, I2, I2, O2, HNil, Out]): Aux[I, HNil, O, Rule[I2, O2], HNil, In, Out] = `n/a`
  }
  abstract class Aux1 {
    // convert R to R :: HNil
    implicit def forAny[I <: HList, L1 <: HList, L2 <: HList, R, Acc <: HList, Out <: HList](implicit x: Aux[I, L1, L2, R :: HNil, Acc, I, Out]): Aux[I, L1, L2, R, Acc, I, Out] = `n/a`
  }
}


sealed trait TakeRight9[L <: HList, Init <: HList, A, B, C, D, E, F, G, H, I]
object TakeRight9 extends LowerPriorityMatchRight9 {
  implicit def forHList9[A, B, C, D, E, F, G, H, I]: TakeRight9[A :: B :: C :: D :: E :: F :: G :: H :: I :: HNil, HNil, A, B, C, D, E, F, G, H, I] = `n/a`
}
private[parboiled2] abstract class LowerPriorityMatchRight9 {
  implicit def forHList[Head, Tail <: HList, Init <: HList, A, B, C, D, E, F, G, H, I]
  (implicit x: TakeRight9[Tail, Init, A, B, C, D, E, F, G, H, I]): TakeRight9[Head :: Tail, Head :: Init, A, B, C, D, E, F, G, H, I] = `n/a`
}