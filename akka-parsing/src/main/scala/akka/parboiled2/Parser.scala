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

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.util.{ Failure, Success, Try }
import scala.util.control.{ NonFatal, NoStackTrace }
import akka.shapeless._
import akka.parboiled2.support._

abstract class Parser(
  initialValueStackSize: Int = 16,
  maxValueStackSize:     Int = 1024) extends RuleDSL {
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
   * Converts a compile-time only rule definition into the corresponding rule method implementation
   * with an explicitly given name.
   */
  def namedRule[I <: HList, O <: HList](name: String)(r: Rule[I, O]): Rule[I, O] = macro ParserMacros.namedRuleImpl[I, O]

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
  def charAt(offset: Int): Char = input.charAt(_cursor + offset)

  /**
   * Same as `charAt` but range-checked.
   * Returns the input character at the index with the given offset from the cursor.
   * If this index is out of range the method returns `EOI`.
   */
  def charAtRC(offset: Int): Char = {
    val ix = _cursor + offset
    if (0 <= ix && ix < input.length) input.charAt(ix) else EOI
  }

  /**
   * Allows "raw" (i.e. untyped) access to the `ValueStack`.
   * In most cases you shouldn't need to access the value stack directly from your code.
   * Use only if you know what you are doing!
   */
  def valueStack: ValueStack = _valueStack

  /**
   * The maximum number of error traces that parser will collect in case of a parse error.
   * Override with a custom value if required.
   * Set to zero to completely disable error trace collection (which will cause `formatError`
   * to no be able to render any "expected" string!).
   */
  def errorTraceCollectionLimit: Int = 24

  /**
   * Formats the given [[ParseError]] into a String using the given formatter instance.
   */
  def formatError(error: ParseError, formatter: ErrorFormatter = new ErrorFormatter()): String =
    formatter.format(error, input)

  ////////////////////// INTERNAL /////////////////////////

  // the char at the current input index
  private var _cursorChar: Char = _

  // the index of the current input char
  private var _cursor: Int = _

  // the value stack instance we operate on
  private var _valueStack: ValueStack = _

  // the current ErrorAnalysisPhase or null (in the initial run)
  private var phase: ErrorAnalysisPhase = _

  def copyStateFrom(other: Parser, offset: Int): Unit = {
    _cursorChar = other._cursorChar
    _cursor = other._cursor - offset
    _valueStack = other._valueStack
    phase = other.phase
    if (phase ne null) phase.applyOffset(offset)
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __inErrorAnalysis = phase ne null

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __run[L <: HList](rule: ⇒ RuleN[L])(implicit scheme: Parser.DeliveryScheme[L]): scheme.Result = {
    def runRule(): Boolean = {
      _cursor = -1
      __advance()
      valueStack.clear()
      try rule ne null
      catch {
        case CutError ⇒ false
      }
    }

    def phase0_initialRun() = {
      _valueStack = new ValueStack(initialValueStackSize, maxValueStackSize)
      runRule()
    }

    def phase1_establishPrincipalErrorIndex(): Int = {
      val phase1 = new EstablishingPrincipalErrorIndex()
      phase = phase1
      if (runRule()) sys.error("Parsing unexpectedly succeeded while trying to establish the principal error location")
      phase1.maxCursor
    }

    def phase2_establishReportedErrorIndex(principalErrorIndex: Int) = {
      val phase2 = new EstablishingReportedErrorIndex(principalErrorIndex)
      phase = phase2
      if (runRule()) sys.error("Parsing unexpectedly succeeded while trying to establish the reported error location")
      phase2
    }

    def phase3_determineReportQuiet(reportedErrorIndex: Int): Boolean = {
      phase = new DetermineReportQuiet(reportedErrorIndex)
      try {
        if (runRule()) sys.error("Parsing unexpectedly succeeded while trying to determine quiet reporting")
        true // if we got here we can only reach the reportedErrorIndex via quiet rules
      } catch {
        case UnquietMismatch ⇒ false // we mismatched beyond the reportedErrorIndex outside of a quiet rule
      }
    }

    @tailrec
    def phase4_collectRuleTraces(reportedErrorIndex: Int, principalErrorIndex: Int, reportQuiet: Boolean)(
      phase3: CollectingRuleTraces     = new CollectingRuleTraces(reportedErrorIndex, reportQuiet),
      traces: VectorBuilder[RuleTrace] = new VectorBuilder): ParseError = {

      def done = {
        val principalErrorPos = Position(principalErrorIndex, input)
        val reportedErrorPos = if (reportedErrorIndex != principalErrorIndex) Position(reportedErrorIndex, input) else principalErrorPos
        ParseError(reportedErrorPos, principalErrorPos, traces.result())
      }
      if (phase3.traceNr < errorTraceCollectionLimit) {
        val trace: RuleTrace =
          try {
            phase = phase3
            runRule()
            null // we managed to complete the run w/o exception, i.e. we have collected all traces
          } catch {
            case e: TracingBubbleException ⇒ e.trace
          }
        if (trace eq null) done
        else phase4_collectRuleTraces(reportedErrorIndex, principalErrorIndex,
          reportQuiet)(new CollectingRuleTraces(reportedErrorIndex, reportQuiet, phase3.traceNr + 1), traces += trace)
      } else done
    }

    try {
      if (phase0_initialRun())
        scheme.success(valueStack.toHList[L]())
      else {
        val principalErrorIndex = phase1_establishPrincipalErrorIndex()
        val p2 = phase2_establishReportedErrorIndex(principalErrorIndex)
        val reportQuiet = phase3_determineReportQuiet(principalErrorIndex)
        val parseError = phase4_collectRuleTraces(p2.reportedErrorIndex, principalErrorIndex, reportQuiet)()
        scheme.parseError(parseError)
      }
    } catch {
      case e: Parser.Fail ⇒
        val pos = Position(cursor, input)
        scheme.parseError(ParseError(pos, pos, RuleTrace(Nil, RuleTrace.Fail(e.expected)) :: Nil))
      case NonFatal(e) ⇒
        scheme.failure(e)
    } finally {
      phase = null
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
    phase match {
      case x: EstablishingPrincipalErrorIndex ⇒ if (_cursor > x.maxCursor) x.maxCursor = _cursor
      case _                                  ⇒ // nothing to do
    }
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
  def __enterNotPredicate(): AnyRef = {
    val saved = phase
    phase = null
    saved
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __exitNotPredicate(saved: AnyRef): Unit = phase = saved.asInstanceOf[ErrorAnalysisPhase]

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __enterAtomic(start: Int): Boolean =
    phase match {
      case null ⇒ false
      case x: EstablishingReportedErrorIndex if x.currentAtomicStart == Int.MinValue ⇒
        x.currentAtomicStart = start
        true
      case _ ⇒ false
    }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __exitAtomic(saved: Boolean): Unit =
    if (saved) {
      phase match {
        case x: EstablishingReportedErrorIndex ⇒ x.currentAtomicStart = Int.MinValue
        case _                                 ⇒ throw new IllegalStateException
      }
    }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __enterQuiet(): Int =
    phase match {
      case x: DetermineReportQuiet ⇒
        if (x.inQuiet) -1
        else {
          x.inQuiet = true
          0
        }
      case x: CollectingRuleTraces if !x.reportQuiet ⇒
        val saved = x.minErrorIndex
        x.minErrorIndex = Int.MaxValue // disables triggering of StartTracingException in __registerMismatch
        saved
      case _ ⇒ -1
    }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __exitQuiet(saved: Int): Unit =
    if (saved >= 0) {
      phase match {
        case x: DetermineReportQuiet ⇒ x.inQuiet = false
        case x: CollectingRuleTraces ⇒ x.minErrorIndex = saved
        case _                       ⇒ throw new IllegalStateException
      }
    }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __registerMismatch(): Boolean = {
    phase match {
      case null | _: EstablishingPrincipalErrorIndex ⇒ // nothing to do
      case x: CollectingRuleTraces ⇒
        if (_cursor >= x.minErrorIndex) {
          if (x.errorMismatches == x.traceNr) throw Parser.StartTracingException else x.errorMismatches += 1
        }
      case x: EstablishingReportedErrorIndex ⇒
        if (x.currentAtomicStart > x.maxAtomicErrorStart) x.maxAtomicErrorStart = x.currentAtomicStart
      case x: DetermineReportQuiet ⇒
        // stop this run early because we just learned that reporting quiet traces is unnecessary
        if (_cursor >= x.minErrorIndex & !x.inQuiet) throw UnquietMismatch
    }
    false
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __bubbleUp(terminal: RuleTrace.Terminal): Nothing = __bubbleUp(Nil, terminal)

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __bubbleUp(prefix: List[RuleTrace.NonTerminal], terminal: RuleTrace.Terminal): Nothing =
    throw new TracingBubbleException(RuleTrace(prefix, terminal))

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
      if (_cursorChar == string.charAt(ix)) {
        __advance()
        __matchString(string, ix + 1)
      } else false
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchStringWrapped(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (_cursorChar == string.charAt(ix)) {
        __advance()
        __updateMaxCursor()
        __matchStringWrapped(string, ix + 1)
      } else {
        try __registerMismatch()
        catch {
          case Parser.StartTracingException ⇒
            import RuleTrace._
            __bubbleUp(NonTerminal(StringMatch(string), -ix) :: Nil, CharMatch(string charAt ix))
        }
      }
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchIgnoreCaseString(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (Character.toLowerCase(_cursorChar) == string.charAt(ix)) {
        __advance()
        __matchIgnoreCaseString(string, ix + 1)
      } else false
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchIgnoreCaseStringWrapped(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (Character.toLowerCase(_cursorChar) == string.charAt(ix)) {
        __advance()
        __updateMaxCursor()
        __matchIgnoreCaseStringWrapped(string, ix + 1)
      } else {
        try __registerMismatch()
        catch {
          case Parser.StartTracingException ⇒
            import RuleTrace._
            __bubbleUp(NonTerminal(IgnoreCaseString(string), -ix) :: Nil, IgnoreCaseChar(string charAt ix))
        }
      }
    else true

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchAnyOf(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      if (string.charAt(ix) == _cursorChar) __advance()
      else __matchAnyOf(string, ix + 1)
    else false

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  @tailrec final def __matchNoneOf(string: String, ix: Int = 0): Boolean =
    if (ix < string.length)
      _cursorChar != EOI && string.charAt(ix) != _cursorChar && __matchNoneOf(string, ix + 1)
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
  def __matchMapWrapped(m: Map[String, Any]): Boolean = {
    val keys = m.keysIterator
    val start = _cursor
    try {
      while (keys.hasNext) {
        val mark = __saveState
        val key = keys.next()
        if (__matchStringWrapped(key)) {
          __push(m(key))
          return true
        } else __restoreState(mark)
      }
      false
    } catch {
      case e: TracingBubbleException ⇒ e.bubbleUp(RuleTrace.MapMatch(m), start)
    }
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __hardFail(expected: String) = throw new Parser.Fail(expected)

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  class TracingBubbleException(private var _trace: RuleTrace) extends RuntimeException with NoStackTrace {
    def trace = _trace
    def bubbleUp(key: RuleTrace.NonTerminalKey, start: Int): Nothing = throw prepend(key, start)
    def prepend(key: RuleTrace.NonTerminalKey, start: Int): this.type = {
      val offset = phase match {
        case x: CollectingRuleTraces ⇒ start - x.minErrorIndex
        case _                       ⇒ throw new IllegalStateException
      }
      _trace = _trace.copy(prefix = RuleTrace.NonTerminal(key, offset) :: _trace.prefix)
      this
    }
  }

  protected class __SubParserInput extends ParserInput {
    val offset = _cursor // the number of chars the input the sub-parser sees is offset from the outer input start
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
  object StartTracingException extends RuntimeException with NoStackTrace

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  object CutError extends RuntimeException with NoStackTrace

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  object UnquietMismatch extends RuntimeException with NoStackTrace

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  class Fail(val expected: String) extends RuntimeException with NoStackTrace

  // error analysis happens in 4 phases:
  // 0: initial run, no error analysis
  // 1: EstablishingPrincipalErrorIndex (1 run)
  // 2: EstablishingReportedErrorIndex (1 run)
  // 3: CollectingRuleTraces (n runs)
  private sealed trait ErrorAnalysisPhase {
    def applyOffset(offset: Int): Unit
  }

  // establish the max cursor value reached in a run
  private class EstablishingPrincipalErrorIndex(var maxCursor: Int = 0) extends ErrorAnalysisPhase {
    def applyOffset(offset: Int) = maxCursor -= offset
  }

  // establish the largest match-start index of all outermost atomic rules
  // that we are in when mismatching at the principal error index
  // or -1 if no atomic rule fails with a mismatch at the principal error index
  private class EstablishingReportedErrorIndex(
    private var _principalErrorIndex: Int,
    var currentAtomicStart:           Int = Int.MinValue,
    var maxAtomicErrorStart:          Int = Int.MinValue) extends ErrorAnalysisPhase {
    def reportedErrorIndex = if (maxAtomicErrorStart >= 0) maxAtomicErrorStart else _principalErrorIndex
    def applyOffset(offset: Int) = {
      _principalErrorIndex -= offset
      if (currentAtomicStart != Int.MinValue) currentAtomicStart -= offset
      if (maxAtomicErrorStart != Int.MinValue) maxAtomicErrorStart -= offset
    }
  }

  // determine whether the reported error location can only be reached via quiet rules
  // in which case we need to report them even though they are marked as "quiet"
  private class DetermineReportQuiet(
    private var _minErrorIndex: Int, // the smallest index at which a mismatch triggers a StartTracingException
    var inQuiet:                Boolean = false // are we currently in a quiet rule?
  ) extends ErrorAnalysisPhase {
    def minErrorIndex = _minErrorIndex
    def applyOffset(offset: Int) = _minErrorIndex -= offset
  }

  // collect the traces of all mismatches happening at an index >= minErrorIndex (the reported error index)
  // by throwing a StartTracingException which gets turned into a TracingBubbleException by the terminal rule
  private class CollectingRuleTraces(
    var minErrorIndex:   Int, // the smallest index at which a mismatch triggers a StartTracingException
    val reportQuiet:     Boolean, // do we need to trace mismatches from quiet rules?
    val traceNr:         Int     = 0, // the zero-based index number of the RuleTrace we are currently building
    var errorMismatches: Int     = 0 // the number of times we have already seen a mismatch at >= minErrorIndex
  ) extends ErrorAnalysisPhase {
    def applyOffset(offset: Int) = minErrorIndex -= offset
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
    import ctx.universe._
    val ruleName =
      ctx.enclosingMethod match {
        case DefDef(_, name, _, _, _, _) ⇒ name.decoded
        case _                           ⇒ ctx.abort(r.tree.pos, "`rule` can only be used from within a method")
      }
    namedRuleImpl(ctx)(ctx.Expr[String](Literal(Constant(ruleName))))(r)
  }

  def namedRuleImpl[I <: HList: ctx.WeakTypeTag, O <: HList: ctx.WeakTypeTag](ctx: ParserContext)(name: ctx.Expr[String])(r: ctx.Expr[Rule[I, O]]): ctx.Expr[Rule[I, O]] = {
    val opTreeCtx = new OpTreeContext[ctx.type] { val c: ctx.type = ctx }
    val opTree = opTreeCtx.RuleCall(Left(opTreeCtx.OpTree(r.tree)), name.tree)
    import ctx.universe._
    val ruleTree = q"""
      def wrapped: Boolean = ${opTree.render(wrapped = true)}
      val matched =
        if (__inErrorAnalysis) wrapped
        else ${opTree.render(wrapped = false)}
      if (matched) akka.parboiled2.Rule else null""" // we encode the "matched" boolean as 'ruleResult ne null'

    reify { ctx.Expr[RuleX](ruleTree).splice.asInstanceOf[Rule[I, O]] }
  }
}