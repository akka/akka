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

package akka.parboiled2

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.util.{ Failure, Success, Try }
import scala.util.control.{ NonFatal, NoStackTrace }
import akka.shapeless._
import akka.parboiled2.support._

abstract class Parser(initialValueStackSize: Int = 16,
                      maxValueStackSize: Int = 1024) extends RuleDSL {
  import Parser._

  require(maxValueStackSize <= 65536, "`maxValueStackSize` > 2^16 is not supported") // due to current snapshot design

  /**
   * The input this parser instance is running against.
   */
  def input: ParserInput

  /**
   * Converts a compile-time only rule definition into the corresponding rule method implementation.
   */
  def rule[I <: HList, O <: HList](r: Rule[I, O]): Rule[I, O] = macro ParserMacros.ruleImpl[I, O]

  /**
   * The index of the next (yet unmatched) input character.
   * Might be equal to `input.length`!
   */
  def cursor: Int = _cursor

  /**
   * The next (yet unmatched) input character, i.e. the one at the `cursor` index.
   * Identical to `if (cursor < input.length) input.charAt(cursor) else EOI` but more efficient.
   */
  def cursorChar: Char = _cursorChar

  /**
   * Returns the last character that was matched, i.e. the one at index cursor - 1
   * Note: for performance optimization this method does *not* do a range check,
   * i.e. depending on the ParserInput implementation you might get an exception
   * when calling this method before any character was matched by the parser.
   */
  def lastChar: Char = charAt(-1)

  /**
   * Returns the character at the input index with the given delta to the cursor.
   * Note: for performance optimization this method does *not* do a range check,
   * i.e. depending on the ParserInput implementation you might get an exception
   * when calling this method before any character was matched by the parser.
   */
  def charAt(offset: Int): Char = input.charAt(cursor + offset)

  /**
   * Same as `charAt` but range-checked.
   * Returns the input character at the index with the given offset from the cursor.
   * If this index is out of range the method returns `EOI`.
   */
  def charAtRC(offset: Int): Char = {
    val ix = cursor + offset
    if (0 <= ix && ix < input.length) input.charAt(ix) else EOI
  }

  /**
   * Allows "raw" (i.e. untyped) access to the `ValueStack`.
   * In most cases you shouldn't need to access the value stack directly from your code.
   * Use only if you know what you are doing!
   */
  def valueStack: ValueStack = _valueStack

  /**
   * Pretty prints the given `ParseError` instance in the context of the `ParserInput` of this parser.
   */
  def formatError(error: ParseError, showExpected: Boolean = true, showPosition: Boolean = true,
                  showLine: Boolean = true, showTraces: Boolean = false): String = {
    val sb = new java.lang.StringBuilder(formatErrorProblem(error))
    import error._
    if (showExpected) sb.append(", expected ").append(formatExpectedAsString)
    if (showPosition) sb.append(" (line ").append(position.line).append(", column ").append(position.column).append(')')
    if (showLine) sb.append(':').append('\n').append(formatErrorLine(error))
    if (showTraces) sb.append('\n').append('\n').append(formatTraces)
    sb.toString
  }

  /**
   * Pretty prints the input line in which the error occurred and underlines the error position in the line
   * with a caret.
   */
  def formatErrorProblem(error: ParseError): String =
    if (error.position.index < input.length) s"Invalid input '${CharUtils.escape(input charAt error.position.index)}'"
    else "Unexpected end of input"

  /**
   * Pretty prints the input line in which the error occurred and underlines the error position in the line
   * with a caret.
   */
  def formatErrorLine(error: ParseError): String =
    (input getLine error.position.line) + '\n' + (" " * (error.position.column - 1) + '^')

  ////////////////////// INTERNAL /////////////////////////

  // the char at the current input index
  private var _cursorChar: Char = _

  // the index of the current input char
  private var _cursor: Int = _

  // the value stack instance we operate on
  private var _valueStack: ValueStack = _

  // the highest input index we have seen in the current run
  // special value: -1 (not collecting errors)
  private var maxCursor: Int = _

  // the number of times we have already seen a character mismatch at the error index
  private var mismatchesAtErrorCursor: Int = _

  // the index of the RuleStack we are currently constructing
  // for the ParseError to be (potentially) returned in the current parser run,
  // special value: -1 (during the run to establish the error location (maxCursor))
  private var currentErrorRuleStackIx: Int = _

  def copyStateFrom(other: Parser, offset: Int): Unit = {
    _cursorChar = other._cursorChar
    _cursor = other._cursor - offset
    _valueStack = other._valueStack
    maxCursor = other.maxCursor - offset
    mismatchesAtErrorCursor = other.mismatchesAtErrorCursor
    currentErrorRuleStackIx = other.currentErrorRuleStackIx
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __collectingErrors = maxCursor >= 0

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __run[L <: HList](rule: ⇒ RuleN[L])(implicit scheme: Parser.DeliveryScheme[L]): scheme.Result = {
    def runRule(errorRuleStackIx: Int = -1): Boolean = {
      _cursor = -1
      __advance()
      valueStack.clear()
      mismatchesAtErrorCursor = 0
      currentErrorRuleStackIx = errorRuleStackIx
      rule ne null
    }

    @tailrec
    def errorPosition(ix: Int = 0, line: Int = 1, col: Int = 1): Position =
      if (ix >= maxCursor) Position(maxCursor, line, col)
      else if (ix >= input.length || input.charAt(ix) != '\n') errorPosition(ix + 1, line, col + 1)
      else errorPosition(ix + 1, line + 1, 1)

    @tailrec
    def buildParseError(errorRuleIx: Int = 0, traces: VectorBuilder[RuleTrace] = new VectorBuilder): ParseError = {
      val ruleFrames: List[RuleFrame] =
        try {
          runRule(errorRuleIx)
          Nil // we managed to complete the run w/o exception, i.e. we have collected all frames
        } catch {
          case e: Parser.CollectingRuleStackException ⇒ e.ruleFrames
        }
      if (ruleFrames.isEmpty) ParseError(errorPosition(), traces.result())
      else buildParseError(errorRuleIx + 1, traces += RuleTrace(ruleFrames.toVector))
    }

    _valueStack = new ValueStack(initialValueStackSize, maxValueStackSize)
    try {
      maxCursor = -1
      if (runRule())
        scheme.success(valueStack.toHList[L]())
      else {
        maxCursor = 0 // establish the error location with the next run
        if (runRule()) sys.error("Parsing unexpectedly succeeded while trying to establish the error location")
        // now maxCursor holds the error location, we can now build the parser error info
        scheme.parseError(buildParseError())
      }
    } catch {
      case NonFatal(e) ⇒ scheme.failure(e)
    }
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __advance(): Boolean = {
    var c = _cursor
    val max = input.length
    if (c < max) {
      c += 1
      _cursor = c
      _cursorChar = if (c == max) EOI else input charAt c
    }
    true
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __updateMaxCursor(): Boolean = {
    if (_cursor > maxCursor) maxCursor = _cursor
    true
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __saveState: Mark = new Mark((_cursor.toLong << 32) + (_cursorChar.toLong << 16) + valueStack.size)

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __restoreState(mark: Mark): Unit = {
    _cursor = (mark.value >>> 32).toInt
    _cursorChar = ((mark.value >>> 16) & 0x000000000000FFFF).toChar
    valueStack.size = (mark.value & 0x000000000000FFFF).toInt
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __enterNotPredicate: Int = {
    val saved = maxCursor
    maxCursor = -1 // disables maxCursor update as well as error rulestack collection
    saved
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __exitNotPredicate(saved: Int): Unit = maxCursor = saved

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __registerMismatch(): Boolean = {
    if (currentErrorRuleStackIx >= 0 && _cursor == maxCursor) {
      if (mismatchesAtErrorCursor < currentErrorRuleStackIx) mismatchesAtErrorCursor += 1
      else throw new Parser.CollectingRuleStackException
    }
    false
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __push(value: Any): Boolean = {
    value match {
      case ()       ⇒
      case x: HList ⇒ valueStack.pushAll(x)
      case x        ⇒ valueStack.push(x)
    }
    true
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchString(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (cursorChar == string.charAt(ix)) {
        __advance()
        __matchString(string, ix + 1)
      } else false
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchStringWrapped(string: String, ruleName: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (cursorChar == string.charAt(ix)) {
        __advance()
        __updateMaxCursor()
        __matchStringWrapped(string, ruleName, ix + 1)
      } else {
        try __registerMismatch()
        catch {
          case e: Parser.CollectingRuleStackException ⇒
            e.save(RuleFrame(RuleFrame.StringMatch(string), ruleName), RuleFrame.CharMatch(string charAt ix))
        }
      }
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchIgnoreCaseString(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (Character.toLowerCase(cursorChar) == string.charAt(ix)) {
        __advance()
        __matchIgnoreCaseString(string, ix + 1)
      } else false
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchIgnoreCaseStringWrapped(string: String, ruleName: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (Character.toLowerCase(cursorChar) == string.charAt(ix)) {
        __advance()
        __updateMaxCursor()
        __matchIgnoreCaseStringWrapped(string, ruleName, ix + 1)
      } else {
        try __registerMismatch()
        catch {
          case e: Parser.CollectingRuleStackException ⇒
            e.save(RuleFrame(RuleFrame.IgnoreCaseString(string), ruleName), RuleFrame.IgnoreCaseChar(string charAt ix))
        }
      }
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchAnyOf(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (string.charAt(ix) == cursorChar) __advance()
      else __matchAnyOf(string, ix + 1)
    else false

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchNoneOf(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      cursorChar != EOI && string.charAt(ix) != cursorChar && __matchNoneOf(string, ix + 1)
    else __advance()

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __matchMap(m: Map[String, Any]): Boolean = {
    val keys = m.keysIterator
    while (keys.hasNext) {
      val mark = __saveState
      val key = keys.next()
      if (__matchString(key)) {
        __push(m(key))
        return true
      } else __restoreState(mark)
    }
    false
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __matchMapWrapped(m: Map[String, Any], ruleName: String): Boolean = {
    val keys = m.keysIterator
    try {
      while (keys.hasNext) {
        val mark = __saveState
        val key = keys.next()
        if (__matchStringWrapped(key, "")) {
          __push(m(key))
          return true
        } else __restoreState(mark)
      }
      false
    } catch {
      case e: Parser.CollectingRuleStackException ⇒ e.save(RuleFrame(RuleFrame.MapMatch(m), ruleName))
    }
  }

  protected class __SubParserInput extends ParserInput {
    val offset = cursor // the number of chars the input the sub-parser sees is offset from the outer input start
    def getLine(line: Int): String = ??? // TODO
    def sliceCharArray(start: Int, end: Int): Array[Char] = input.sliceCharArray(start + offset, end + offset)
    def sliceString(start: Int, end: Int): String = input.sliceString(start + offset, end + offset)
    def length: Int = input.length - offset
    def charAt(ix: Int): Char = input.charAt(offset + ix)
  }
}

object Parser {

  trait DeliveryScheme[L <: HList] {
    type Result
    def success(result: L): Result
    def parseError(error: ParseError): Result
    def failure(error: Throwable): Result
  }

  object DeliveryScheme extends AlternativeDeliverySchemes {
    implicit def Try[L <: HList, Out](implicit unpack: Unpack.Aux[L, Out]) =
      new DeliveryScheme[L] {
        type Result = Try[Out]
        def success(result: L) = Success(unpack(result))
        def parseError(error: ParseError) = Failure(error)
        def failure(error: Throwable) = Failure(error)
      }
  }
  sealed abstract class AlternativeDeliverySchemes {
    implicit def Either[L <: HList, Out](implicit unpack: Unpack.Aux[L, Out]) =
      new DeliveryScheme[L] {
        type Result = Either[ParseError, Out]
        def success(result: L) = Right(unpack(result))
        def parseError(error: ParseError) = Left(error)
        def failure(error: Throwable) = throw error
      }
    implicit def Throw[L <: HList, Out](implicit unpack: Unpack.Aux[L, Out]) =
      new DeliveryScheme[L] {
        type Result = Out
        def success(result: L) = unpack(result)
        def parseError(error: ParseError) = throw error
        def failure(error: Throwable) = throw error
      }
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  class Mark private[Parser] (val value: Long) extends AnyVal

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  class CollectingRuleStackException extends RuntimeException with NoStackTrace {
    private[this] var frames = List.empty[RuleFrame]
    def save(newFrames: RuleFrame*): Nothing = {
      frames = newFrames.foldRight(frames)(_ :: _)
      throw this
    }
    def ruleFrames: List[RuleFrame] = frames
  }
}

object ParserMacros {
  import scala.reflect.macros.Context

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  type RunnableRuleContext[L <: HList] = Context { type PrefixType = Rule.Runnable[L] }

  def runImpl[L <: HList: c.WeakTypeTag](c: RunnableRuleContext[L])()(scheme: c.Expr[Parser.DeliveryScheme[L]]): c.Expr[scheme.value.Result] = {
    import c.universe._
    val runCall = c.prefix.tree match {
      case q"parboiled2.this.Rule.Runnable[$l]($ruleExpr)" ⇒ ruleExpr match {
        case q"$p.$r" if p.tpe <:< typeOf[Parser] ⇒ q"val p = $p; p.__run[$l](p.$r)($scheme)"
        case q"$p.$r($args)" if p.tpe <:< typeOf[Parser] ⇒ q"val p = $p; p.__run[$l](p.$r($args))($scheme)"
        case q"$p.$r[$t]" if p.tpe <:< typeOf[Parser] ⇒ q"val p = $p; p.__run[$l](p.$r[$t])($scheme)"
        case q"$p.$r[$t]" if p.tpe <:< typeOf[RuleX] ⇒ q"__run[$l]($ruleExpr)($scheme)"
        case x ⇒ c.abort(x.pos, "Illegal `.run()` call base: " + x)
      }
      case x ⇒ c.abort(x.pos, "Illegal `Runnable.apply` call: " + x)
    }
    c.Expr[scheme.value.Result](runCall)
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  type ParserContext = Context { type PrefixType = Parser }

  def ruleImpl[I <: HList: ctx.WeakTypeTag, O <: HList: ctx.WeakTypeTag](ctx: ParserContext)(r: ctx.Expr[Rule[I, O]]): ctx.Expr[Rule[I, O]] = {
    val opTreeCtx = new OpTreeContext[ctx.type] { val c: ctx.type = ctx }
    val opTree = opTreeCtx.OpTree(r.tree)
    import ctx.universe._
    val ruleName =
      ctx.enclosingMethod match {
        case DefDef(_, name, _, _, _, _) ⇒ name.decoded
        case _                           ⇒ ctx.abort(r.tree.pos, "`rule` can only be used from within a method")
      }
    reify {
      ctx.Expr[RuleX](opTree.renderRule(ruleName)).splice.asInstanceOf[Rule[I, O]]
    }
  }
}