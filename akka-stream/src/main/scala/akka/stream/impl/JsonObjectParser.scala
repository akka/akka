/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.scaladsl.Framing.FramingException
import akka.util.ByteString

import scala.annotation.{ switch }

/**
 * INTERNAL API: Use [[akka.stream.scaladsl.JsonFraming]] instead.
 */
@InternalApi private[akka] object JsonObjectParser {

  final val SquareBraceStart = 91 // '['.toByte
  final val SquareBraceEnd = 93 // ']'.toByte
  final val CurlyBraceStart = 123 // '{'.toByte
  final val CurlyBraceEnd = 125 // '}'.toByte
  final val Colon = 58 // ':'.toByte
  final val DoubleQuote = 34 // '"'.toByte
  final val Backslash = 92 // '\\'.toByte
  final val Comma = 44 // ','.toByte

  final val LineBreak = 10 // '\n'
  final val LineBreak2 = 13 // '\r'
  final val Tab = 9 // '\t'
  final val Space = 32 // ' '

  def isWhitespace(b: Byte): Boolean = (b: @switch) match {
    case Space      ⇒ true
    case LineBreak  ⇒ true
    case LineBreak2 ⇒ true
    case Tab        ⇒ true
    case _          ⇒ false
  }

  private sealed trait ParserState {
    def proceed(input: Byte, pp: JsonObjectParser): Unit
  }

  private object ParserState {

    object UnknownState extends ParserState {
      override def toString: String = "Unknown"

      override def proceed(input: Byte, pp: JsonObjectParser): Unit =
        throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — unknown state")

    }

    object InitialState extends ParserState {
      override def toString: String = "Initial"

      override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        if (input == SquareBraceStart) {
          pp.skip()
          pp.enterState(MainArrayBeforeElement)
        } else if (isWhitespace(input)) {
          pp.skip()
        } else if (input == DoubleQuote) {
          pp.take()
          pp.enterState(InOuterString(NotArrayAfterElement, pp))
        } else if (input == CurlyBraceStart) {
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement)
        } else if (!isWhitespace(input)) {
          pp.take()
          pp.enterState(InOuterNaked(NotArrayAfterElement, pp))
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [$pp.pos] of [$pp.buffer]")
        }
      }
    }

    /**
     * We are in this state whenever we're inside a JSON Array-style stream, before any element
     */
    private object MainArrayBeforeElement extends ParserState {
      override def toString: String = "MainArrayBeforeElement"

      override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case _ if isWhitespace(input) ⇒
          pp.skip()
        case Comma ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}]")
        case SquareBraceEnd ⇒
          pp.skip()
          pp.enterState(AfterMainArray)
        case SquareBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterArray, MainArrayAfterElement)
        case DoubleQuote ⇒
          pp.take()
          pp.enterState(InOuterString(MainArrayAfterElement, pp))
        case CurlyBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterObject, MainArrayAfterElement)
        case _ if !isWhitespace(input) ⇒
          pp.take()
          pp.enterState(InOuterNaked(MainArrayAfterElement, pp))
        case _ ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}]")
      }

    }

    private object AfterMainArray extends ParserState {
      override def toString: String = "AfterMainArray"

      override def proceed(input: Byte, pp: JsonObjectParser): Unit = (input: @switch) match {
        case _ if isWhitespace(input) ⇒
          pp.skip()
        case _ ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after closed JSON-style array")
      }
    }

    private object MainArrayAfterElement extends ParserState {
      override def toString: String = "MainArrayAfterElement"

      /* note: we don't mark the object complete as it's been done, if necessary, as part of the
      exit action that happened just before we ended up here.
       */
      override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case _ if isWhitespace(input) ⇒
          pp.skip()
        case Comma ⇒
          pp.skip()
          pp.enterState(MainArrayBeforeElement)
        case SquareBraceEnd ⇒
          pp.skip()
          pp.enterState(AfterMainArray)
        case _ ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after JSON-style array element")
      }
    }

    private trait LeafParserState extends ParserState {
      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState
    }

    private object AfterBackslash extends LeafParserState {
      override def toString: String = "AfterBackslash"

      override def proceed(input: Byte, pp: JsonObjectParser): Unit = {
        pp.take()
        pp.enterState(pp.stateAfterBackslash)
        pp.stateAfterBackslash = UnknownState
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
        pp.objectDone()
        pp.leaveContainer()
      }
    }

    private abstract class InStringBase extends LeafParserState with HasExitAction {
      def exitAction(pp: JsonObjectParser): Unit

      def apply(nextState: ParserState, pp: JsonObjectParser): ParserState = {
        pp.stateAfterStringValue = nextState
        pp.enterState(this)
      }

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case Backslash ⇒
          pp.take()
          pp.enterState(AfterBackslash(this, pp))

        case DoubleQuote ⇒
          pp.take()
          pp.enterState(pp.stateAfterStringValue)
          pp.stateAfterStringValue = UnknownState
          exitAction(pp)

        case LineBreak | LineBreak2 ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — line break in string")

        case _ ⇒
          pp.take()
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

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case Comma | SquareBraceEnd | CurlyBraceEnd | LineBreak ⇒
          finish(input, pp)

        case _ if isWhitespace(input) ⇒
          finish(input, pp)

        case _ if !isWhitespace(input) ⇒
          pp.take()
      }
    }

    private object InNaked extends InNakedBase with HasEmptyExitAction {
      override def toString: String = "InNaked"
    }
    private object InOuterNaked extends InNakedBase with CompleteObjectOnExitAction {
      override def toString: String = "InOuterNaked"
    }

    private abstract class InContainerBase(containerEnd: Int) extends ParserState with HasExitAction {
      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case _ if isWhitespace(input) ⇒
          pp.take()

        case _ if input == containerEnd ⇒
          /* this is our end! */
          pp.take()
          exitAction(pp)

        case DoubleQuote ⇒
          pp.take()
          pp.enterState(InString(this, pp))

        case Comma | Colon ⇒
          /* in a real JSON parser we'd check whether the colon and commas appear at appropriate places. Here
          we do without: we're just framing JSON and this is good enough */
          pp.take()

        case CurlyBraceStart ⇒
          pp.take()
          pp.enterContainer(InObject, this)

        case SquareBraceStart ⇒
          pp.take()
          pp.enterContainer(InArray, this)

        case _ ⇒
          pp.take()
          pp.enterState(InNaked(this, pp))

        //case _ =>
        //  throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}]")
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
      override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case Comma ⇒
          pp.skip()
          pp.enterState(CommaSeparatedBeforeElement)

        case LineBreak ⇒
          pp.skip()
          pp.enterState(LinebreakSeparatedBeforeElement)

        case _ if isWhitespace(input) ⇒
          pp.skip()

        /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
          the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

          We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
          will be considered to be a JSON Array stream
           */
        case CurlyBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterObject, NotArrayAfterElement)

        case _ ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in linebreak or comma-separated stream")
      }
    }

    private abstract sealed class SeparatorSeparatedAfterElement extends ParserState {
      def separator: Int
      def separatorName: String

      def beforeNextItem: ParserState

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case _ if input == separator ⇒
          pp.skip()
          pp.enterState(beforeNextItem)

        case _ if isWhitespace(input) ⇒
          pp.skip()

        /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
        the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

        We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
        will be considered to be a JSON Array stream
         */
        case CurlyBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterObject, this)

        case _ ⇒
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — junk after value in $separatorName-separated stream")

      }
    }

    private abstract sealed class SeparatorSeparatedBeforeElement extends ParserState {
      def afterState: SeparatorSeparatedAfterElement

      final override def proceed(input: Byte, pp: JsonObjectParser): Unit = input match {
        case DoubleQuote ⇒
          pp.take()
          pp.enterState(InOuterString(afterState, pp))

        case SquareBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterArray, afterState)

        case CurlyBraceStart ⇒
          pp.take()
          pp.enterContainer(InOuterObject, afterState)

        case _ if input == afterState.separator ⇒
          pp.skip()
        /* note: effectively, empty lines are tolerated, ignored and skipped. Throw a FramingError if desired otherwise */

        case _ if isWhitespace(input) ⇒
          pp.skip()

        case _ if !isWhitespace(input) ⇒
          pp.take()
          pp.enterState(InOuterNaked(afterState, pp))
      }
    }

    private object LinebreakSeparatedBeforeElement extends SeparatorSeparatedBeforeElement {
      override def toString: String = "LinebreakSeparatedBeforeElement"

      override def afterState: SeparatorSeparatedAfterElement = LinebreakSeparatedAfterElement
    }

    private object LinebreakSeparatedAfterElement extends SeparatorSeparatedAfterElement {
      override def toString: String = "LinebreakSeparatedAfterElement"

      override def separator: Int = LineBreak

      override def separatorName: String = "linebreak"

      override def beforeNextItem: ParserState = LinebreakSeparatedBeforeElement
    }

    private object CommaSeparatedBeforeElement extends SeparatorSeparatedBeforeElement {
      override def toString: String = "CommaSeparatedBeforeEement"

      override def afterState: SeparatorSeparatedAfterElement = CommaSeparatedAfterElement
    }

    private object CommaSeparatedAfterElement extends SeparatorSeparatedAfterElement {
      override def toString: String = "CommaSeparatedAfterElement"

      override def separator: Int = Comma

      override def separatorName: String = "comma"

      override def beforeNextItem: ParserState = CommaSeparatedBeforeElement
    }

  }

  private sealed class ContainerStackLevel(_pp: JsonObjectParser, _current: ParserState, _stateAtExit: ParserState,
                                           _previous: ContainerStackLevel) {
    var current: ParserState = _current
    var stateAtExit: ParserState = _stateAtExit

    var previous: ContainerStackLevel = _previous
    var next: ContainerStackLevel = _

    val pp: JsonObjectParser = _pp

    def level: Int = previous match {
      case null ⇒ 0
      case p    ⇒ 1 + p.level
    }

    override def toString: String = previous match {
      case null ⇒ s"Root($pp)"
      case _    ⇒ s"Regular(_, ${current}, ${stateAtExit}) → ${previous}"
    }
  }

  private object ContainerStackLevel {
    def Root(pp: JsonObjectParser): ContainerStackLevel = new ContainerStackLevel(pp, ParserState.UnknownState, ParserState.UnknownState, null)

    def Regular(pp: JsonObjectParser, current: ParserState, stateAtExit: ParserState, previous: ContainerStackLevel): ContainerStackLevel = {
      /* this slightly ugly routine re-uses ContainerStackLevels in order to avoid allocating during stream processing */

      previous.next match {
        case null ⇒
          val n = new ContainerStackLevel(pp, current, stateAtExit, previous)
          previous.next = n
          n
        case reuse ⇒
          reuse.current = current
          reuse.stateAtExit = stateAtExit
          reuse.previous = previous
          // pp.containerStack.next = reuse
          reuse
      }
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
  private var pos = 0 // latest position of pointer while scanning for json object end
  private var trimFront = 0 // number of chars to drop from the front of the bytestring before emitting (skip whitespace etc)
  private var charsInObject = 0
  private var completedObject = false
  private var state: ParserState = ParserState.InitialState

  private var containerStack: ContainerStackLevel = ContainerStackLevel.Root(this)
  private var stateAfterBackslash: ParserState = ParserState.UnknownState
  private var stateAfterStringValue: ParserState = ParserState.UnknownState
  private var stateAfterNakedValue: ParserState = ParserState.UnknownState

  private def enterContainer(current: ParserState, next: ParserState): ParserState = {
    containerStack = ContainerStackLevel.Regular(this, current, next, containerStack)
    enterState(current)
  }

  private def leaveContainer(): ParserState = {
    val nextState = containerStack.stateAtExit
    containerStack = containerStack.previous
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

  @inline private def enterState(state: ParserState): ParserState = {
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
    buffer ++= input

  def isEmpty: Boolean = buffer.isEmpty

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
        case _ ⇒
          val (emit, buf) = buffer.splitAt(pos)
          buffer = buf.compact
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
  }

  /** @return true if an entire valid JSON object was found, false otherwise */
  private def seekObject(): Boolean = {
    completedObject = false

    val bufSize = buffer.size
    val maxPos = Math.min(bufSize, maximumObjectLength)

    while ((pos < maxPos) && !completedObject) {
      state.proceed(buffer(pos), this)
    }

    if (pos >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    completedObject
  }

}
