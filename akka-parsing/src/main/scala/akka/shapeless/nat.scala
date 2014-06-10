/*
 * Copyright (c) 2011-13 Miles Sabin 
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

import scala.language.experimental.macros

import scala.reflect.macros.Context

/**
 * Base trait for type level natural numbers.
 *
 * @author Miles Sabin
 */
trait Nat {
  type N <: Nat
}

/**
 * Encoding of successor.
 *
 * @author Miles Sabin
 */
case class Succ[P <: Nat]() extends Nat {
  type N = Succ[P]
}

/**
 * Encoding of zero.
 *
 * @author Miles Sabin
 */
class _0 extends Nat {
  type N = _0
}

/**
 * Type level encoding of the natural numbers.
 *
 * @author Miles Sabin
 */
object Nat extends Nats {
  import ops.nat._

  def apply(i: Int): Nat = macro NatMacros.materializeWidened

  /** The natural number 0 */
  type _0 = akka.shapeless._0
  val _0: _0 = new _0

  def toInt[N <: Nat](implicit toIntN: ToInt[N]) = toIntN()

  def toInt(n: Nat)(implicit toIntN: ToInt[n.N]) = toIntN()

  implicit def materialize(i: Int): Nat = macro NatMacros.materializeSingleton
}

object NatMacros {
  def mkNatTpt(c: Context)(i: c.Expr[Int]): c.Tree = {
    import c.universe._

    val n = i.tree match {
      case Literal(Constant(n: Int)) ⇒ n
      case _ ⇒
        c.abort(c.enclosingPosition, s"Expression ${i.tree} does not evaluate to an Int constant")
    }

    if (n < 0)
      c.abort(c.enclosingPosition, s"A Nat cannot represent $n")

    val succSym = typeOf[Succ[_]].typeConstructor.typeSymbol
    val _0Sym = typeOf[_0].typeSymbol

    def mkNatTpt(n: Int): Tree = {
      if (n == 0) Ident(_0Sym)
      else AppliedTypeTree(Ident(succSym), List(mkNatTpt(n - 1)))
    }

    mkNatTpt(n)
  }

  def materializeSingleton(c: Context)(i: c.Expr[Int]): c.Expr[Nat] = {
    import c.universe._

    val natTpt = mkNatTpt(c)(i)

    val pendingSuperCall = Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())

    val moduleName = newTermName(c.fresh("nat_"))
    val moduleDef =
      ModuleDef(Modifiers(), moduleName,
        Template(
          List(natTpt),
          emptyValDef,
          List(
            DefDef(
              Modifiers(), nme.CONSTRUCTOR, List(),
              List(List()),
              TypeTree(),
              Block(List(pendingSuperCall), Literal(Constant(())))))))

    c.Expr[Nat] {
      Block(
        List(moduleDef),
        Ident(moduleName))
    }
  }

  def materializeWidened(c: Context)(i: c.Expr[Int]): c.Expr[Nat] = {
    import c.universe._
    val natTpt = mkNatTpt(c)(i)

    val valName = newTermName(c.fresh("nat_"))
    val valDef =
      ValDef(Modifiers(), valName,
        natTpt,
        Apply(Select(New(natTpt), nme.CONSTRUCTOR), List()))

    c.Expr[Nat] {
      Block(
        List(valDef),
        Ident(valName))
    }
  }
}
