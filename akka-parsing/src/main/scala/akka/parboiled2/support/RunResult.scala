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
import akka.parboiled2._

// phantom type, only used for rule DSL typing
sealed trait RunResult[T] {
  type Out <: RuleX
}

object RunResult {
  implicit def fromAux[T, Out0 <: RuleX](implicit aux: Aux[T, Out0]): RunResult[T] { type Out = Out0 } = `n/a`

  sealed trait Aux[T, Out]
  object Aux extends Aux1 {
    implicit def forRule[R <: RuleX]: Aux[R, R] = `n/a`
    //implicit def forFHList[I <: HList, R, In0 <: HList, Out0 <: HList](implicit x: JA[I, R, In0, Out0]): Aux[I ⇒ R, Rule[In0, Out0]] = `n/a`
  }
  abstract class Aux1 extends Aux2 {
    implicit def forF1[Z, R, In0 <: HList, Out0 <: HList](implicit x: JA[Z :: HNil, R, In0, Out0]): Aux[Z ⇒ R, Rule[In0, Out0]] = `n/a`
    implicit def forF2[Y, Z, R, In0 <: HList, Out0 <: HList](implicit x: JA[Y :: Z :: HNil, R, In0, Out0]): Aux[(Y, Z) ⇒ R, Rule[In0, Out0]] = `n/a`
    implicit def forF3[X, Y, Z, R, In0 <: HList, Out0 <: HList](implicit x: JA[X :: Y :: Z :: HNil, R, In0, Out0]): Aux[(X, Y, Z) ⇒ R, Rule[In0, Out0]] = `n/a`
    implicit def forF4[W, X, Y, Z, R, In0 <: HList, Out0 <: HList](implicit x: JA[W :: X :: Y :: Z :: HNil, R, In0, Out0]): Aux[(W, X, Y, Z) ⇒ R, Rule[In0, Out0]] = `n/a`
    implicit def forF5[V, W, X, Y, Z, R, In0 <: HList, Out0 <: HList](implicit x: JA[V :: W :: X :: Y :: Z :: HNil, R, In0, Out0]): Aux[(V, W, X, Y, Z) ⇒ R, Rule[In0, Out0]] = `n/a`
  }

  abstract class Aux2 {
    protected type JA[I <: HList, R, In0 <: HList, Out0 <: HList] = Join.Aux[I, HNil, HNil, R, HNil, In0, Out0]
    implicit def forAny[T]: Aux[T, Rule0] = `n/a`
  }
}