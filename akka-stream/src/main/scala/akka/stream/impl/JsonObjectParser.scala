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
    protected final def sameState: ParserState = this

    private def debugState(pp: JsonObjectParser, nextState: ParserState): String = {
      val wholebuf = pp.buffer ++ pp.nextBuffer
      val (before, after) = wholebuf.splitAt(pp.pos)
      val trans = if (nextState == this) this.toString else s"$this→$nextState"

      s"${before.utf8String} <${trans}> ${after.utf8String}"
    }

    @inline
    protected def proceed(input: Byte, pp: JsonObjectParser): ParserState

    def seekNextEvent(pp: JsonObjectParser): Boolean
  }

  private sealed trait ParserStateMachinery {
    this: ParserState ⇒

    /* this is a performance trick

    the code below really belongs to [[ParserState]]. Putting it into a non-leftmost trait within each final (leaf)
    object will trick scalac into duplicating the code for the benefit of the JVM, which will in turn trick the JVM into
    considering each leaf type's instance of the code as different for the purpose of making inline decisions.

    This in turn causes (on OpenJDK 8 through 10 at least) the JRE to optimize each ParserState without accidentally
    accreting too much unrelated code.
     */

    @tailrec
    final def seekNextEvent(pp: JsonObjectParser): Boolean = {
      val nextState = proceed(pp.nextInput, pp)

      // println(debugState(pp, nextState))

      if (pp.keepSeeking) {
        if (nextState eq this) {
          /* same-type tailrec: inline */
          seekNextEvent(pp)
        } else {
          /* sibling-type tailrec: not inlined, but it turns out it works, too */
          // nextState.seekNextEvent(pp)
          true
        }
      } else {
        false
      }
    }
  }

  private object ParserState {

    object UnknownState extends ParserState with ParserStateMachinery {
      override def toString: String = "Unknown"

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState =
        throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — unknown state")

    }

    object InitialState extends ParserState with ParserStateMachinery {
      override def toString: String = "Initial"

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (input == SquareBraceStart) {
          pp.skip()
          pp.enterState(MainArrayBeforeElement)
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(NotArrayAfterElement, pp))
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement)
        } else if (isWhitespace(input)) {
          pp.skip()
          sameState
        } else {
          pp.take()
          pp.enterState(InOuterNaked(NotArrayAfterElement, pp))
        }
      }
    }

    /**
     * We are in this state whenever we're inside a JSON Array-style stream, before any element
     */
    private object MainArrayBeforeElement extends ParserState with ParserStateMachinery {
      override def toString: String = "MainArrayBeforeElement"

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (isWhitespace(input)) {
          pp.skip()
          sameState
        } else if (input == Comma) {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}]")
        } else if (input == SquareBraceEnd) {
          pp.skip()
          pp.enterState(AfterMainArray)
        } else if (input == SquareBraceStart) {
          pp.take()
          pp.enterContainer(InOuterArray, MainArrayAfterElement)
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(MainArrayAfterElement, pp))
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, MainArrayAfterElement)
        } else {
          pp.take()
          pp.enterState(InOuterNaked(MainArrayAfterElement, pp))
        }
      }

    }

    private object AfterMainArray extends ParserState with ParserStateMachinery {
      override def toString: String = "AfterMainArray"

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState =
        if (isWhitespace(input)) {
          pp.skip()
          sameState
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after closed JSON-style array")
        }
    }

    private object MainArrayAfterElement extends ParserState with ParserStateMachinery {
      override def toString: String = "MainArrayAfterElement"

      /* note: we don't mark the object complete as it's been done, if necessary, as part of the
      exit action that happened just before we ended up here.
       */
      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (isWhitespace(input)) {
          pp.skip()
          sameState
        } else if (input == Comma) {
          pp.skip()
          pp.enterState(MainArrayBeforeElement)
        } else if (input == SquareBraceEnd) {
          pp.skip()
          pp.enterState(AfterMainArray)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after JSON-style array element")
        }
      }
    }

    private trait LeafParserState extends ParserState {
      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState
    }

    private object AfterBackslash extends LeafParserState with ParserStateMachinery {
      override def toString: String = "AfterBackslash"

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        pp.take()
        val nstate = pp.enterState(pp.stateAfterBackslash)
        pp.stateAfterBackslash = UnknownState
        nstate
      }

      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterBackslash = nextState
        pp.enterState(this)
        this
      }
    }

    private trait HasExitAction { this: ParserState ⇒
      def exitAction(pp: JsonObjectParser): ParserState
    }

    private trait HasEmptyExitAction extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): ParserState = pp.state
    }
    private trait CompleteObjectOnExitAction extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): ParserState = {
        pp.objectDone()
        pp.state
      }
    }

    private trait LeaveContainerOnExit extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): ParserState = {
        pp.leaveContainer()
      }
    }

    private trait CompleteObjectAndLeaveContainerOnExit extends HasExitAction { this: ParserState ⇒
      final def exitAction(pp: JsonObjectParser): ParserState = {
        val next = pp.leaveContainer()
        pp.objectDone()
        next
      }
    }

    private abstract class InStringBase extends LeafParserState with HasExitAction {
      def exitAction(pp: JsonObjectParser): ParserState

      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterStringValue = nextState
        pp.enterState(this)
      }

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (input == Backslash) {
          pp.take()
          pp.enterState(AfterBackslash(this, pp))
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(pp.stateAfterStringValue)
          exitAction(pp)
        } else if ((input == LineBreak) || (input == LineBreak2)) {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — line break in string")
        } else {
          pp.take()
          sameState
        }
      }
    }

    private object InString extends InStringBase with HasEmptyExitAction with ParserStateMachinery {
      override def toString: String = "InString"
    }
    private object InOuterString extends InStringBase with CompleteObjectOnExitAction with ParserStateMachinery {
      override def toString: String = "InOuterString"
    }

    private abstract class InNakedBase extends LeafParserState with HasExitAction {
      final def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterNakedValue = nextState
        pp.enterState(this)
        this
      }

      private def finish(input: Byte, pp: JsonObjectParser): ParserState = {
        val nextState = pp.stateAfterNakedValue
        pp.stateAfterNakedValue = UnknownState

        pp.enterState(nextState)
        exitAction(pp)
        nextState
      }

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if ((input == Comma) || (input == SquareBraceEnd) || (input == CurlyBraceEnd) || (input == LineBreak)) {
          finish(input, pp)

        } else if (isWhitespace(input)) {
          finish(input, pp)
        } else {
          pp.take()
          sameState
        }
      }
    }

    private object InNaked extends InNakedBase with HasEmptyExitAction with ParserStateMachinery {
      override def toString: String = "InNaked"
    }
    private object InOuterNaked extends InNakedBase with CompleteObjectOnExitAction with ParserStateMachinery {
      override def toString: String = "InOuterNaked"
    }

    private abstract class InContainerBase(containerEnd: Int) extends ParserState with HasExitAction {

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {

        /* within a container, we always take whichever character we find. We'll act after. */
        pp.take()

        if (input == DoubleQuote) {
          pp.enterState(InString(this, pp))
        } else if ((input == Comma) || (input == Colon)) {
          /* in a real JSON parser we'd check whether the colon and commas appear at appropriate places. Here
            we do without: we're just framing JSON and this is good enough */
          sameState
        } else if (input == CurlyBraceStart) {
          pp.enterContainer(InObject, this)
        } else if (input == SquareBraceStart) {
          pp.enterContainer(InArray, this)
        } else if (input == containerEnd) {
          /* this is our end! */
          exitAction(pp)
        } else if (isWhitespace(input)) {
          sameState
        } else {
          pp.enterState(InNaked(this, pp))
        }
      }
    }

    private object InArray extends InContainerBase(SquareBraceEnd) with LeaveContainerOnExit with ParserStateMachinery {
      override def toString: String = "InArray"
    }
    private object InOuterArray extends InContainerBase(SquareBraceEnd) with CompleteObjectAndLeaveContainerOnExit with ParserStateMachinery {
      override def toString: String = "InOuterArray"
    }

    private object InObject extends InContainerBase(CurlyBraceEnd) with LeaveContainerOnExit with ParserStateMachinery {
      override def toString: String = "InObject"
    }
    private object InOuterObject extends InContainerBase(CurlyBraceEnd) with CompleteObjectAndLeaveContainerOnExit with ParserStateMachinery {
      override def toString: String = "InOuterObject"
    }

    private object NotArrayAfterElement extends ParserState with ParserStateMachinery {
      override def toString: String = "NotArrayAfter"

      /* in this state we know we are not in a JSON array-formatted stream, but we don't yet know yet what kind of
      separator is being used. We'll never revisit this state or InitialState once we meet a separator
       */
      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (input == Comma) {
          pp.skip()
          pp.enterState(CommaSeparatedBeforeElement)
        } else if (input == LineBreak) {
          pp.skip()
          pp.enterState(LinebreakSeparatedBeforeElement)
        } else if (isWhitespace(input)) {
          pp.skip()

          /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
            the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

            We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
            will be considered to be a JSON Array stream
             */
          sameState
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in linebreak or comma-separated stream")
        }
      }
    }

    private abstract sealed class SeparatorSeparatedAfterElement(val separator: Int) extends ParserState {
      def separatorName: String

      def beforeNextItem: ParserState

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
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
          sameState
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, this)
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in $separatorName-separated stream")
        }
      }
    }

    private abstract sealed class SeparatorSeparatedBeforeElement(val afterState: SeparatorSeparatedAfterElement) extends ParserState {
      private val separator = afterState.separator

      final override def proceed(input: Byte, pp: JsonObjectParser): ParserState = {
        if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(afterState, pp))
        } else if (input == SquareBraceStart) {
          pp.take()
          pp.enterContainer(InOuterArray, afterState)
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, afterState)
        } else if (input == separator) {
          pp.skip()
          /* note: effectively, empty lines are tolerated, ignored and skipped. Throw a FramingError if desired otherwise */
          sameState
        } else if (isWhitespace(input)) {
          pp.skip()
          sameState
        } else {
          pp.take()
          pp.enterState(InOuterNaked(afterState, pp))
        }
      }
    }

    private object LinebreakSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(LinebreakSeparatedAfterElement) with ParserStateMachinery {
      override def toString: String = "LinebreakSeparatedBeforeElement"
    }

    private object LinebreakSeparatedAfterElement extends SeparatorSeparatedAfterElement(LineBreak) with ParserStateMachinery {
      override def toString: String = "LinebreakSeparatedAfterElement"

      override def separatorName: String = "linebreak"

      override def beforeNextItem: ParserState = LinebreakSeparatedBeforeElement
    }

    private object CommaSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(CommaSeparatedAfterElement) with ParserStateMachinery {
      override def toString: String = "CommaSeparatedBeforeEement"
    }

    private object CommaSeparatedAfterElement extends SeparatorSeparatedAfterElement(Comma) with ParserStateMachinery {
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

  /** @return true if an entire valid JSON object was found, false otherwise */
  private def seekObject(): Boolean = {
    completedObject = false
    maxSeekPos = Math.min(buffer.size, maximumObjectLength)

    if (internalSeekObject()) {
      true
    } else {
      /* we haven't yet found a completedObject, but we might have additional data lying around */

      if ((pos == maxSeekPos) && nextBuffer.nonEmpty) {
        /* ah, we've reached the end of buffer but the nextBuffer contains things. Let's keep trying for a bit */

        buffer = (if (buffer.isEmpty) {
          nextBuffer
        } else {
          buffer ++ nextBuffer
        }).compact
        /* compacting the buffer here does cause a copy of the underlying bytes but then ensures that nextInput will
            always result in calling into the same subtype of ByteBuffer, which helps the JRE better inline */
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
    if (keepSeeking) {
      while (state.seekNextEvent(this)) {
        // keep going
      }
    }

    if (pos >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    completedObject
  }

}
