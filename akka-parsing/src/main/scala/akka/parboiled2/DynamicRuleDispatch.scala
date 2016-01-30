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

package akka.parboiled2

import scala.collection.immutable
import scala.reflect.macros.Context
import akka.shapeless.HList

/**
 * An application needs to implement this interface to receive the result
 * of a dynamic parsing run.
 * Often times this interface is directly implemented by the Parser class itself
 * (even though this is not a requirement).
 */
trait DynamicRuleHandler[P <: Parser, L <: HList] extends Parser.DeliveryScheme[L] {
  def parser: P
  def ruleNotFound(ruleName: String): Result
}

/**
 * Runs one of the rules of a parser instance of type `P` given the rules name.
 * The rule must have type `RuleN[L]`.
 */
trait DynamicRuleDispatch[P <: Parser, L <: HList] {
  def apply(handler: DynamicRuleHandler[P, L], ruleName: String): handler.Result
}

object DynamicRuleDispatch {

  /**
   * Implements efficient runtime dispatch to a predefined set of parser rules.
   * Given a number of rule names this macro-supported method creates a `DynamicRuleDispatch` instance along with
   * a sequence of the given rule names.
   * Note that there is no reflection involved and compilation will fail, if one of the given rule names
   * does not constitute a method of parser type `P` or has a type different from `RuleN[L]`.
   */
  def apply[P <: Parser, L <: HList](ruleNames: String*): (DynamicRuleDispatch[P, L], immutable.Seq[String]) = macro __create[P, L]

  ///////////////////// INTERNAL ////////////////////////

  def __create[P <: Parser, L <: HList](c: Context)(ruleNames: c.Expr[String]*)(implicit P: c.WeakTypeTag[P], L: c.WeakTypeTag[L]): c.Expr[(DynamicRuleDispatch[P, L], immutable.Seq[String])] = {
    import c.universe._
    val names: Array[String] = ruleNames.map {
      _.tree match {
        case Literal(Constant(s: String)) ⇒ s
        case x                            ⇒ c.abort(x.pos, s"Invalid `String` argument `x`, only `String` literals are supported!")
      }
    }(collection.breakOut)
    java.util.Arrays.sort(names.asInstanceOf[Array[Object]])

    def rec(start: Int, end: Int): Tree =
      if (start <= end) {
        val mid = (start + end) >>> 1
        val name = names(mid)
        q"""val c = $name compare ruleName
            if (c < 0) ${rec(mid + 1, end)}
            else if (c > 0) ${rec(start, mid - 1)}
            else {
              val p = handler.parser
              p.__run[$L](p.${newTermName(name).encodedName.toTermName})(handler)
            }"""
      } else q"handler.ruleNotFound(ruleName)"

    c.Expr[(DynamicRuleDispatch[P, L], immutable.Seq[String])] {
      q"""val drd =
            new akka.parboiled2.DynamicRuleDispatch[$P, $L] {
              def apply(handler: akka.parboiled2.DynamicRuleHandler[$P, $L], ruleName: String): handler.Result =
                 ${rec(0, names.length - 1)}
            }
          (drd, scala.collection.immutable.Seq(..$ruleNames))"""
    }
  }
}