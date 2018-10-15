/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.scaladsl.Framing.FramingException
import akka.util.ByteString

import scala.annotation.{ switch, tailrec }

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 */
@InternalApi private[akka] object JsonObjectParser {

  final val SquareBraceStart = 91 // '['
  final val SquareBraceEnd = 93 // ']'
  final val CurlyBraceStart = 123 // '{'
  final val CurlyBraceEnd = 125 // '}'
  final val Colon = 58 // ':'
  final val DoubleQuote = 34 // '"'
  final val Backslash = 92 // '\\'
  final val Comma = 44 // ','

  final val LineBreak = 10 // '\n'
  final val LineBreak2 = 13 // '\r'
  final val Tab = 9 // '\t'
  final val Space = 32 // ' '

  @inline private def isWhitespace(b: Byte): Boolean =
    (b == Space) || (b == LineBreak) || (b == LineBreak2) || (b == Tab)

  private sealed trait ParserState {
    def proceed(input: Byte, pp: JsonObjectParser): Unit

    final def proceedIfPossible(pp: JsonObjectParser): Unit = {
      if (pp.keepSeeking) {
        pp.proceedKnownNextIfPossible(this)
      }
    }
  }

  private object ParserState {

    object UnknownState extends ParserState {
      override def toString: String = "Unknown"

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit =
        throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — unknown state")

    }

    object InitialState extends ParserState {
      override def toString: String = "Initial"

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == SquareBraceStart) {
          pp.skip()
          pp.enterState(MainArrayBeforeElement).proceedIfPossible(pp)
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(NotArrayAfterElement, pp)).proceedIfPossible(pp)
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement).proceedIfPossible(pp)
        } else if (isWhitespace(input)) {
          pp.skip()

          if (pp.keepSeeking) {
            /* this.proceedIfProssible(pp) — inlined for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else {
          pp.take()
          pp.enterState(InOuterNaked(NotArrayAfterElement, pp)).proceedIfPossible(pp)
        }
      }
    }

    /**
     * We are in this state whenever we're inside a JSON Array-style stream, before any element
     */
    private object MainArrayBeforeElement extends ParserState {
      override def toString: String = "MainArrayBeforeElement"

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (isWhitespace(input)) {
          pp.skip()
          if (pp.keepSeeking) {
            /* this.proceedIfPossible(pp) — inlined for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else if (input == Comma) {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}]")
        } else if (input == SquareBraceEnd) {
          pp.skip()
          pp.enterState(AfterMainArray)
        } else if (input == SquareBraceStart) {
          pp.take()
          pp.enterContainer(InOuterArray, MainArrayAfterElement).proceedIfPossible(pp)
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(MainArrayAfterElement, pp)).proceedIfPossible(pp)

        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, MainArrayAfterElement).proceedIfPossible(pp)
        } else {
          pp.take()
          pp.enterState(InOuterNaked(MainArrayAfterElement, pp)).proceedIfPossible(pp)
        }
      }

    }

    private object AfterMainArray extends ParserState {
      override def toString: String = "AfterMainArray"

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit =
        if (isWhitespace(input)) {
          pp.skip()
          if (pp.keepSeeking) {
            /* this.proceedIfPossible(pp) — inlined for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after closed JSON-style array")
        }
    }

    private object MainArrayAfterElement extends ParserState {
      override def toString: String = "MainArrayAfterElement"

      /* note: we don't mark the object complete as it's been done, if necessary, as part of the
      exit action that happened just before we ended up here.
       */
      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (isWhitespace(input)) {
          pp.skip()
          if (pp.keepSeeking) {
            proceed(pp.nextInput, pp)
          }
        } else if (input == Comma) {
          pp.skip()
          pp.enterState(MainArrayBeforeElement)
        } else if (input == SquareBraceEnd) {
          pp.skip()
          pp.enterState(AfterMainArray).proceedIfPossible(pp)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after JSON-style array element")
        }
      }
    }

    private trait LeafParserState extends ParserState {
      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState
    }

    private object AfterBackslash extends LeafParserState {
      override def toString: String = "AfterBackslash"

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        pp.take()
        val n = pp.enterState(pp.stateAfterBackslash)
        pp.stateAfterBackslash = UnknownState
        pp.proceedKnownNextIfPossible(n)
      }

      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterBackslash = nextState
        pp.enterState(this)
        this
      }
    }

    private trait HasExitAction { this: ParserState ⇒
      def exitAction(pp: JsonObjectParser): Unit
    }

    private trait HasEmptyExitAction extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): Unit = {}
    }
    private trait CompleteObjectOnExitAction extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): Unit = pp.objectDone()
    }

    private trait LeaveContainerOnExit extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): Unit = {
        pp.leaveContainer()
      }
    }

    private trait CompleteObjectAndLeaveContainerOnExit extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): Unit = {
        pp.leaveContainer()
        pp.objectDone()
      }
    }

    private abstract class InStringBase extends LeafParserState with HasExitAction {
      def exitAction(pp: JsonObjectParser): Unit

      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterStringValue = nextState
        pp.enterState(this)
      }

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == Backslash) {
          pp.take()
          pp.enterState(AfterBackslash(this, pp)).proceedIfPossible(pp)
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(pp.stateAfterStringValue)
          exitAction(pp)
        } else if ((input == LineBreak) || (input == LineBreak2)) {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — line break in string")
        } else {
          pp.take()
          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        }
      }
    }

    private object InString extends InStringBase with HasEmptyExitAction {
      override def toString: String = "InString"
    }
    private object InOuterString extends InStringBase with CompleteObjectOnExitAction {
      override def toString: String = "InOuterString"
    }

    private abstract class InNakedBase extends LeafParserState with HasExitAction {
      final def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterNakedValue = nextState
        pp.enterState(this)
        this
      }

      private def finish(input: Byte, pp: JsonObjectParser): Unit = {
        val nextState = pp.stateAfterNakedValue
        pp.stateAfterNakedValue = UnknownState

        pp.enterState(nextState)
        exitAction(pp)
        nextState.proceed(input, pp)
      }

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if ((input == Comma) || (input == SquareBraceEnd) || (input == CurlyBraceEnd) || (input == LineBreak)) {
          finish(input, pp)

        } else if (isWhitespace(input)) {
          finish(input, pp)
        } else {
          pp.take()
          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        }
      }
    }

    private object InNaked extends InNakedBase with HasEmptyExitAction {
      override def toString: String = "InNaked"
    }
    private object InOuterNaked extends InNakedBase with CompleteObjectOnExitAction {
      override def toString: String = "InOuterNaked"
    }

    private abstract class InContainerBase(containerEnd: Int) extends ParserState with HasExitAction {

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {

        /* within a container, we always take whichever character we find. We'll act after. */
        pp.take()

        if (input == DoubleQuote) {
          pp.enterState(InString(this, pp)).proceedIfPossible(pp)
        } else if ((input == Comma) || (input == Colon)) {
          /* in a real JSON parser we'd check whether the colon and commas appear at appropriate places. Here
            we do without: we're just framing JSON and this is good enough */
          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else if (input == CurlyBraceStart) {
          pp.enterContainer(InObject, this).proceedIfPossible(pp)
        } else if (input == SquareBraceStart) {
          pp.enterContainer(InArray, this).proceedIfPossible(pp)
        } else if (input == containerEnd) {
          /* this is our end! */
          exitAction(pp)
        } else if (isWhitespace(input)) {
          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(ppfor tailrec */
            proceed(pp.nextInput, pp)
          }
        } else {
          pp.enterState(InNaked(this, pp)).proceedIfPossible(pp)
        }
      }
    }

    private object InArray extends InContainerBase(SquareBraceEnd) with LeaveContainerOnExit {
      override def toString: String = "InArray"
    }
    private object InOuterArray extends InContainerBase(SquareBraceEnd) with CompleteObjectAndLeaveContainerOnExit {
      override def toString: String = "InOuterArray"
    }

    private object InObject extends InContainerBase(CurlyBraceEnd) with LeaveContainerOnExit {
      override def toString: String = "InObject"
    }
    private object InOuterObject extends InContainerBase(CurlyBraceEnd) with CompleteObjectAndLeaveContainerOnExit {
      override def toString: String = "InOuterObject"
    }

    private object NotArrayAfterElement extends ParserState {
      override def toString: String = "NotArrayAfter"

      /* in this state we know we are not in a JSON array-formatted stream, but we don't yet know yet what kind of
      separator is being used. We'll never revisit this state or InitialState once we meet a separator
       */
      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == Comma) {
          pp.skip()
          pp.enterState(CommaSeparatedBeforeElement).proceedIfPossible(pp)
        } else if (input == LineBreak) {
          pp.skip()
          pp.enterState(LinebreakSeparatedBeforeElement).proceedIfPossible(pp)
        } else if (isWhitespace(input)) {
          pp.skip()

          /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
            the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

            We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
            will be considered to be a JSON Array stream
             */
          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement).proceedIfPossible(pp)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in linebreak or comma-separated stream")
        }
      }
    }

    private abstract sealed class SeparatorSeparatedAfterElement(val separator: Int) extends ParserState {
      def separatorName: String

      def beforeNextItem: ParserState

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == separator) {
          pp.skip()
          pp.enterState(beforeNextItem)
        } else if (isWhitespace(input)) {
          pp.skip()

          /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
            the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

            We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
            will be considered to be a JSON Array stream
             */
          if (pp.keepSeeking) {
            /* inlining this.proceedKnownNextIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, this).proceedIfPossible(pp)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in $separatorName-separated stream")

        }
      }
    }

    private abstract sealed class SeparatorSeparatedBeforeElement(val afterState: SeparatorSeparatedAfterElement) extends ParserState {
      private val separator = afterState.separator

      @tailrec
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(afterState, pp)).proceedIfPossible(pp)
        } else if (input == SquareBraceStart) {
          pp.take()
          pp.enterContainer(InOuterArray, afterState).proceedIfPossible(pp)
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, afterState).proceedIfPossible(pp)
        } else if (input == separator) {
          pp.skip()
          /* note: effectively, empty lines are tolerated, ignored and skipped. Throw a FramingError if desired otherwise */

          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }

        } else if (isWhitespace(input)) {
          pp.skip()

          if (pp.keepSeeking) { /* inlining this.proceedIfPossible(pp) for tailrec */
            proceed(pp.nextInput, pp)
          }
        } else {
          pp.take()
          pp.enterState(InOuterNaked(afterState, pp)).proceedIfPossible(pp)
        }
      }
    }

    private object LinebreakSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(LinebreakSeparatedAfterElement) {
      override def toString: String = "LinebreakSeparatedBeforeElement"
    }

    private object LinebreakSeparatedAfterElement extends SeparatorSeparatedAfterElement(LineBreak) {
      override def toString: String = "LinebreakSeparatedAfterElement"

      override def separatorName: String = "linebreak"

      override def beforeNextItem: ParserState = LinebreakSeparatedBeforeElement
    }

    private object CommaSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(CommaSeparatedAfterElement) {
      override def toString: String = "CommaSeparatedBeforeEement"
    }

    private object CommaSeparatedAfterElement extends SeparatorSeparatedAfterElement(Comma) {
      override def toString: String = "CommaSeparatedAfterElement"

      override def separatorName: String = "comma"

      override def beforeNextItem: ParserState = CommaSeparatedBeforeElement
    }

  }
}

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 *
 * **Mutable** framing implementation that given any number of [[ByteString]] chunks, can emit JSON objects contained within them.
 * Typically JSON objects are separated by new-lines or commas, however a top-level JSON Array can also be understood and chunked up
 * into valid JSON objects by this framing implementation.
 *
 * Leading whitespace between elements will be trimmed.
 */
@InternalApi private[akka] class JsonObjectParser(maximumObjectLength: Int = Int.MaxValue) {
  import JsonObjectParser._

  private var buffer: ByteString = ByteString.empty
  private var nextBuffer: ByteString = ByteString.empty
  private var pos = 0 // latest position of pointer while scanning for json object end
  private var trimFront = 0 // number of chars to drop from the front of the bytestring before emitting (skip whitespace etc)
  private var charsInObject = 0
  private var maxSeekPos: Int = _ // where to stop looking within the buffer in the current 'poll' operation
  private var completedObject = false
  private var state: ParserState = ParserState.InitialState

  private var stateAfterBackslash: ParserState = ParserState.UnknownState
  private var stateAfterStringValue: ParserState = ParserState.UnknownState
  private var stateAfterNakedValue: ParserState = ParserState.UnknownState

  private var containerStackState: Array[ParserState] = Array.ofDim(8)
  private var currentContainerStackLevel: Int = -1

  private def enterContainer[S <: ParserState](current: S, next: ParserState): S = {
    currentContainerStackLevel = currentContainerStackLevel + 1

    if (containerStackState.size == currentContainerStackLevel) {
      /* deep object hierarchies? Grow a bit to accomodate */
      val ncss = Array.ofDim[ParserState](containerStackState.size + (containerStackState.size / 2))
      java.lang.System.arraycopy(containerStackState, 0, ncss, 0, containerStackState.size)
      containerStackState = ncss
    }
    containerStackState(currentContainerStackLevel) = next
    enterState(current)
  }

  private def leaveContainer(): ParserState = {
    if (currentContainerStackLevel < 0)
      throw new FramingException(s"Invalid JSON encountered at position [${pos}] of [${buffer}] — trying to close too many levels")

    val nextState = containerStackState(currentContainerStackLevel)
    currentContainerStackLevel = currentContainerStackLevel - 1
    enterState(nextState)
  }

  @inline private def skip(): Unit = {
    pos += 1
    if (charsInObject == 0) trimFront += 1
  }

  @inline private def take(): Unit = {
    pos += 1
    charsInObject += 1
  }

  @inline private def enterState[S <: ParserState](state: S): S = {
    this.state = state
    state
  }

  private def objectDone(): Unit = {
    completedObject = true
  }

  private def debugCurrentSelection: String =
    buffer.take(pos).drop(trimFront).take(charsInObject).utf8String

  /**
   * Appends input ByteString to internal byte string buffer.
   * Use [[poll]] to extract contained JSON objects.
   */
  def offer(input: ByteString): Unit =
    nextBuffer ++= input

  def isEmpty: Boolean = buffer.isEmpty && nextBuffer.isEmpty

  /**
   * Attempt to locate next complete JSON object in buffered ByteString and returns `Some(it)` if found.
   * May throw a [[akka.stream.scaladsl.Framing.FramingException]] if the contained JSON is invalid or max object size is exceeded.
   */
  def poll(): Option[ByteString] = {
    val foundObject = seekObject()
    if (!foundObject) None
    else
      (pos: @switch) match {
        case -1 | 0 ⇒ None
        case _      ⇒ emitItem()
      }
  }

  private def emitItem(): Option[ByteString] = {
    val emit = buffer.take(pos)
    val buf = buffer.drop(pos)
    buffer = buf /* We don't compact; meaning we'll keep the underlying memory "as is" until the polled objects
                          have all been consumed. Presumably, a JSON parse operation is to happen soon and these
                          ByteStrings will become irrelevant and GC'd */
    pos = 0

    val tf = trimFront
    trimFront = 0

    val cio = charsInObject
    charsInObject = 0

    val result = if (tf == 0) {
      val trimmed = emit.take(cio)
      if (trimmed.isEmpty) None
      else Some(trimmed)
    } else {
      val trimmed = emit.drop(tf).take(cio)
      if (trimmed.isEmpty) None
      else Some(trimmed)
    }
    result
  }

  @inline private def keepSeeking: Boolean = (pos < maxSeekPos) && !completedObject
  @inline private def nextInput: Byte = buffer(pos)

  @inline private def proceedKnownNextIfPossible[S <: ParserState](next: S): Unit = {
    if (keepSeeking) {
      next.proceed(nextInput, this)
    }
  }

  /** @return true if an entire valid JSON object was found, false otherwise */
  private def seekObject(): Boolean = {
    completedObject = false
    maxSeekPos = Math.min(buffer.size, maximumObjectLength)

    if (internalSeekObject()) {
      true
    } else {
      /* we haven't found a completedObject */

      if ((pos == maxSeekPos) && nextBuffer.nonEmpty) {
        /* ah, we've reached the end of buffer but the nextBuffer contains things. Let's keep trying for a bit */

        buffer = (buffer ++ nextBuffer).compact // TBD, do we compact or not? Do we compact whole or just next?
        nextBuffer = ByteString.empty
        maxSeekPos = Math.min(buffer.size, maximumObjectLength)

        // we can (should) retry once. No use doing it more, since nextBuffer can't be filled again in the meantime.
        internalSeekObject()
      } else {
        false
      }
    }
  }

  private def internalSeekObject(): Boolean = {
    while (keepSeeking) {
      state.proceed(nextInput, this)
    }

    if (pos >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    completedObject
  }

}
