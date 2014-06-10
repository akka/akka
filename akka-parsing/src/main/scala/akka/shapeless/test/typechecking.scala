/*
 * Copyright (c) 2013 Miles Sabin 
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

package akka.shapeless.test

import scala.language.experimental.macros

import java.util.regex.Pattern

import scala.reflect.macros.{ Context, TypecheckException }

/**
 * A utility which ensures that a code fragment does not typecheck.
 *
 * Credit: Stefan Zeiger (@StefanZeiger)
 */
object illTyped {
  def apply(code: String): Unit = macro applyImplNoExp
  def apply(code: String, expected: String): Unit = macro applyImpl

  def applyImplNoExp(c: Context)(code: c.Expr[String]) = applyImpl(c)(code, null)

  def applyImpl(c: Context)(code: c.Expr[String], expected: c.Expr[String]): c.Expr[Unit] = {
    import c.universe._

    val Expr(Literal(Constant(codeStr: String))) = code
    val (expPat, expMsg) = expected match {
      case null ⇒ (null, "Expected some error.")
      case Expr(Literal(Constant(s: String))) ⇒
        (Pattern.compile(s, Pattern.CASE_INSENSITIVE), "Expected error matching: " + s)
    }

    try {
      c.typeCheck(c.parse("{ " + codeStr + " }"))
      c.abort(c.enclosingPosition, "Type-checking succeeded unexpectedly.\n" + expMsg)
    } catch {
      case e: TypecheckException ⇒
        val msg = e.getMessage
        if ((expected ne null) && !(expPat.matcher(msg)).matches)
          c.abort(c.enclosingPosition, "Type-checking failed in an unexpected way.\n" + expMsg + "\nActual error: " + msg)
    }

    reify(())
  }
}
