/*
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.scaladsl.Framing.FramingException
import akka.util.{ ByteString, CompactByteString }

import scala.collection.{ mutable ⇒ m }
import scala.annotation.tailrec
import scala.reflect.ClassTag

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

  private def isWhitespace(b: Byte): Boolean =
    (b == Space) || (b == LineBreak) || (b == LineBreak2) || (b == Tab)

  private[akka] sealed abstract class ParserState {
    def debugState(nextState: ParserState)(implicit pp: JsonObjectParser): String = {
      val wholebuf = pp.nextBuffers.foldLeft(pp.buffer)(_ ++ _)
      val (before, after) = wholebuf.splitAt(pp.pos)
      val (trim, first) = before.splitAt(pp.trimFront)
      val trans = if (nextState == this) this.toString else s"$this→$nextState"

      s"${pp.preTaken.utf8String} <*> ${trim.utf8String} [[ ${first.utf8String} ]] <${trans}> ${after.utf8String}"
    }

    def inObject: Boolean

    final def enterState[S <: ParserState](state: S)(implicit pp: JsonObjectParser): S = pp.switchToState(state)

    @noinline final protected def throwInvalidJson(cause: String)(implicit pp: JsonObjectParser): ParserState = {
      throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — ${cause}")
    }

    @noinline final protected def throwInvalidJson(cause: String, inner: Throwable)(implicit pp: JsonObjectParser): ParserState = {
      throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] — ${cause}", inner)
    }

    @inline protected def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState

    /**
     * Looks up the next character which causes a state change (including returning a complete object), skipping or
     * taking as appropriate along the way.
     *
     * This code logically belongs to [[JsonObjectParser.seekObject]]. Putting it here enables the JRE to
     * notice and take advantage of opportunities that depend on the current ParserState
     *
     * @return false if there is a complete object ready or the buffer is depleted.
     */
    def seekNextEvent(maxSeekPos: Int)(implicit pp: JsonObjectParser): Boolean

    @tailrec
    final protected def seekNextEventInternal(maxSeekPos: Int)(implicit pp: JsonObjectParser): Boolean = {
      if ((!pp.completedObject) && (pp.pos < maxSeekPos)) {

        val input = pp.buffer(pp.pos)
        val nextState = (evaluateNextCharacter(input): @inline) // must mutate pp.pos OR (inclusive) return a different state

        if (nextState eq this) {
          /* same-type tailrec: inline */
          seekNextEventInternal(maxSeekPos)
        } else {
          // consumed may be 0 or 1 (we'll recompute the steps to the end anyway)

          /* bounce back to the outer loop to enter the next state's own seekNextEvent() loop */
          true
        }
      } else {
        false /* we must stop. Either we found an object or we're out of bytes in the buffer */
      }
    }
  }

  sealed private trait ParserStateMachinery {
    this: ParserState ⇒

    /* for some reason, having this method here (in a mix-in trait) appears to cause OpenJDK's JIT to make better
    inlining choices (i.e. expand evaluateNextCharacter within each copy of seekNextEvent, rather than optimizing the
    common tight loop with an avoidable virtual dispatch right in the middle)
     */

    final def seekNextEvent(maxSeekPos: Int)(implicit pp: JsonObjectParser): Boolean = seekNextEventInternal(maxSeekPos): @inline
  }

  private object ParserState {

    sealed trait TakingOrSkippingState {
      this: ParserState ⇒
    }

    sealed trait TakingState extends TakingOrSkippingState {
      this: ParserState ⇒
      private[akka] def take()(implicit pp: JsonObjectParser): ParserState with TakingState = {
        pp.pos += 1
        this
      }

      override def inObject: Boolean = true
    }

    sealed trait SkippingState extends TakingOrSkippingState {
      this: ParserState ⇒
      private[akka] def skip()(implicit pp: JsonObjectParser): ParserState with SkippingState = {
        pp.pos += 1
        this
      }

      def inObject: Boolean = false
    }

    sealed trait NeitherTakingOrSkippingState extends TakingOrSkippingState {
      this: ParserState ⇒
      def inObject: Boolean = false
    }

    sealed abstract class NonContainerState extends ParserState with SkippingState

    sealed private[akka] abstract class ContainerState extends ParserState with TakingState {
      @inline protected def entering()(implicit pp: JsonObjectParser): Unit = {}

      @inline protected def leaving()(implicit pp: JsonObjectParser): Unit = {}

      @noinline def enter(stateAfter: ParserState)(implicit pp: JsonObjectParser): ContainerState = {
        entering()
        pp.containerStack.append(stateAfter)
        pp.switchToState(this)
      }

      def leave()(implicit pp: JsonObjectParser): ParserState = {
        leaving()

        /* // assumptions — this code is only necessary while testing/troubleshooting
        if (pp.state ne this) {
          throw new FramingException(s"Invalid JSON encountered at position [${pos}] of [${buffer}] — trying to leave a non-container level")
        } */

        try {
          val nextState = pp.containerStack.removeLast()
          pp.switchToState(nextState)
        } catch {
          case e: IllegalStateException ⇒
            throwInvalidJson("trying to close too many levels", e)
        }
      }
    }

    sealed trait TopLevelContainerState {
      this: ContainerState ⇒
      final protected override def entering()(implicit pp: JsonObjectParser): Unit = {
        pp.startObject()
      }

      final protected override def leaving()(implicit pp: JsonObjectParser): Unit = {
        pp.objectDone()
      }
    }

    sealed abstract class InnerLeafContainerState extends ContainerState {
      @inline final override def enter(stateAfter: ParserState)(implicit pp: JsonObjectParser): ContainerState = {
        pp.stateAfterLeafValue = stateAfter
        pp.switchToState(this)
        entering()
        this
      }

      @inline final override def leave()(implicit pp: JsonObjectParser): ParserState = {
        leaving()
        pp.switchToState(pp.stateAfterLeafValue)
      }
    }

    sealed abstract class TopLevelLeafContainerState extends InnerLeafContainerState with TopLevelContainerState

    case object InitialState extends ParserState with SkippingState with ParserStateMachinery {

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (input == SquareBraceStart) {
          enterState(MainArrayBeforeElement)
            .skip()
        } else if (input == DoubleQuote) {
          InOuterString.enter(NotArrayAfterElement)
            .take()
        } else if (input == CurlyBraceStart) {
          InOuterObject.enter(NotArrayAfterElement)
            .take()
        } else if (isWhitespace(input)) {
          this
            .skip()
        } else {
          InOuterNaked.enter(NotArrayAfterElement)
            .take()
        }
      }
    }

    /**
     * We are in this state whenever we're inside a JSON Array-style stream, before any element
     */
    private case object MainArrayBeforeElement extends ParserState with SkippingState with ParserStateMachinery {

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (isWhitespace(input)) {
          this.skip()
        } else if (input == Comma) {
          throwInvalidJson("unexpected symbol: ,")
        } else if (input == SquareBraceEnd) {
          enterState(AfterMainArray)
            .skip()
        } else if (input == SquareBraceStart) {
          InOuterArray.enter(MainArrayAfterElement)
            .take()
        } else if (input == DoubleQuote) {
          InOuterString.enter(MainArrayAfterElement)
            .take()

        } else if (input == CurlyBraceStart) {
          InOuterObject.enter(MainArrayAfterElement)
            .take()
        } else {
          InOuterNaked.enter(MainArrayAfterElement)
            .take()
        }
      }

    }

    private case object AfterMainArray extends ParserState with SkippingState with ParserStateMachinery {

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState with SkippingState =
        if (isWhitespace(input)) {
          this
            .skip()
        } else {
          throw new FramingException(s"Invalid JSON encountered at position [${pp.pos}] of [${pp.buffer}] after closed JSON-style array")
        }
    }

    private case object MainArrayAfterElement extends NonContainerState with SkippingState with ParserStateMachinery {

      /* note: we don't mark the object complete as it's been done, if necessary, as part of the
      exit action that happened just before we ended up here.
       */
      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (isWhitespace(input)) {
          this
            .skip()
        } else if (input == Comma) {
          this.skip()
          enterState(MainArrayBeforeElement)
        } else if (input == SquareBraceEnd) {
          this.skip()
          enterState(AfterMainArray)
        } else {
          throwInvalidJson("after JSON-style array element")
        }
      }
    }

    private case object AfterBackslash extends ContainerState with ParserStateMachinery {

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        take()
        leave()
      }

      override def enter(stateAfter: ParserState)(implicit pp: JsonObjectParser): ContainerState = {
        pp.stateAfterBackslash = stateAfter
        pp.switchToState(this)
      }

      override def leave()(implicit pp: JsonObjectParser): ParserState = {
        pp.switchToState(pp.stateAfterBackslash)
      }
    }

    private sealed trait InStringBase {
      this: ContainerState ⇒

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (input == Backslash) {
          take()
          AfterBackslash.enter(this)
        } else if (input == DoubleQuote) {
          take()
          leave()
        } else if ((input == LineBreak) || (input == LineBreak2)) {
          throwInvalidJson("line break in string")
        } else {
          this
            .take()
        }
      }
    }

    private case object InString extends InnerLeafContainerState with InStringBase with ParserStateMachinery

    private case object InOuterString extends TopLevelLeafContainerState with InStringBase with ParserStateMachinery

    private sealed trait InNakedBase {
      this: ContainerState ⇒
      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if ((input == Comma) || (input == SquareBraceEnd) || (input == CurlyBraceEnd) || (input == LineBreak)) {
          // not skipping, not taking
          leave()
        } else if (isWhitespace(input)) {
          // not skipping, not taking
          leave()
        } else {
          take()
        }
      }
    }

    private case object InNaked extends InnerLeafContainerState with InNakedBase with ParserStateMachinery

    private case object InOuterNaked extends TopLevelLeafContainerState with InNakedBase with ParserStateMachinery

    private sealed abstract class InContainerBase(containerEnd: Int) extends ContainerState {
      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {

        /* within a container, we always take whichever character we find. We'll act after. */
        take()

        if (input == DoubleQuote) {
          InString.enter(this)
        } else if ((input == Comma) || (input == Colon)) {
          /* in a real JSON parser we'd check whether the colon and commas appear at appropriate places. Here
            we do without: we're just framing JSON and this is good enough */
          this
        } else if (input == CurlyBraceStart) {
          InObject.enter(this)
        } else if (input == SquareBraceStart) {
          InArray.enter(this)
        } else if (input == containerEnd) {
          /* this is our end! */
          leave()
        } else if (isWhitespace(input)) {
          this
        } else {
          InNaked.enter(this)
        }
      }
    }

    private sealed abstract class BranchContainer(containerEnd: Int) extends InContainerBase(containerEnd) {
    }

    private sealed abstract class RootContainer(containerEnd: Int) extends InContainerBase(containerEnd) with TopLevelContainerState {

      override def enter(stateAfter: ParserState)(implicit pp: JsonObjectParser): ContainerState = {
        pp.stateAfterRootValue = stateAfter
        pp.switchToState(this)
        entering()
        this
      }

      override def leave()(implicit pp: JsonObjectParser): ParserState = {
        leaving()
        pp.switchToState(pp.stateAfterRootValue)
      }
    }

    private case object InArray extends BranchContainer(SquareBraceEnd) with ParserStateMachinery

    private case object InOuterArray extends RootContainer(SquareBraceEnd) with ParserStateMachinery

    private case object InObject extends BranchContainer(CurlyBraceEnd) with ParserStateMachinery

    private case object InOuterObject extends RootContainer(CurlyBraceEnd) with ParserStateMachinery

    private case object NotArrayAfterElement extends NonContainerState with ParserStateMachinery {

      /* in this state we know we are not in a JSON array-formatted stream, but we don't yet know yet what kind of
      separator is being used. We'll never revisit this state or InitialState once we meet a separator
       */
      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (input == Comma) {
          enterState(CommaSeparatedBeforeElement)
            .skip()
        } else if (input == LineBreak) {
          enterState(LinebreakSeparatedBeforeElement)
            .skip()
        } else if (isWhitespace(input)) {
          /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
            the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

            We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
            will be considered to be a JSON Array stream
             */
          skip()

        } else if (input == CurlyBraceStart) {
          InOuterObject.enter(NotArrayAfterElement)
            .take()
        } else {
          throwInvalidJson("junk after value in linebreak or comma-separated stream")
        }
      }
    }

    private sealed abstract class SeparatorSeparatedAfterElement(val separator: Int) extends NonContainerState {
      def separatorName: String

      def beforeNextItem: ParserState with SkippingState

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (input == separator) {
          skip()
          enterState(beforeNextItem)
        } else if (isWhitespace(input)) {
          /* This is a tolerance; since there is no ambiguity, we can consider a next object immediately after
            the previous, even without a Comma or Linebreak separator. Akka Stream 2.5.16 has historically supported that.

            We can't extend the same tolerance to arrays, as any stream whose first non-whitespace character is a SquareBraceStart
            will be considered to be a JSON Array stream
             */
          skip()
        } else if (input == CurlyBraceStart) {
          InOuterObject.enter(this)
            .take()
        } else {
          @noinline def errorString: String = s"junk after value in $separatorName-separated stream"

          throwInvalidJson(errorString)
        }
      }
    }

    private abstract sealed class SeparatorSeparatedBeforeElement(val afterState: SeparatorSeparatedAfterElement) extends NonContainerState {
      private val separator = afterState.separator

      final override def evaluateNextCharacter(input: Byte)(implicit pp: JsonObjectParser): ParserState = {
        if (input == DoubleQuote) {
          InOuterString.enter(afterState)
            .take()
        } else if (input == SquareBraceStart) {
          InOuterArray.enter(afterState)
            .take()
        } else if (input == CurlyBraceStart) {
          InOuterObject.enter(afterState)
            .take()
        } else if (input == separator) {
          /* note: effectively, empty lines are tolerated, ignored and skipped. Throw a FramingError if desired otherwise */
          skip()
        } else if (isWhitespace(input)) {
          skip()
        } else {
          InOuterNaked.enter(afterState)
            .take()
        }
      }
    }

    private case object LinebreakSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(LinebreakSeparatedAfterElement) with ParserStateMachinery

    private case object LinebreakSeparatedAfterElement extends SeparatorSeparatedAfterElement(LineBreak) with ParserStateMachinery {
      override def separatorName: String = "linebreak"

      override def beforeNextItem: ParserState with SkippingState = LinebreakSeparatedBeforeElement
    }

    private case object CommaSeparatedBeforeElement extends SeparatorSeparatedBeforeElement(CommaSeparatedAfterElement) with ParserStateMachinery

    private case object CommaSeparatedAfterElement extends SeparatorSeparatedAfterElement(Comma) with ParserStateMachinery {

      override def separatorName: String = "comma"

      override def beforeNextItem: ParserState with SkippingState = CommaSeparatedBeforeElement
    }

  }

  sealed private class RingBuffer[A: ClassTag](SizeIncrements: Int = 8) extends m.Iterable[A] {
    var contents: Array[A] = Array.ofDim[A](SizeIncrements)
    var headIndex: Int = 0
    var tailIndex: Int = 0

    def capacity: Int = contents.size

    override def isEmpty: Boolean = headIndex == tailIndex

    override def nonEmpty: Boolean = headIndex != tailIndex

    override def size: Int = (capacity + tailIndex - headIndex) % capacity

    def grow: RingBuffer[A] = {
      val newContents = Array.ofDim[A](capacity + SizeIncrements)
      if (tailIndex > headIndex) {
        Array.copy(contents, headIndex, newContents, 0, tailIndex - headIndex)
      } else {
        Array.copy(contents, headIndex, newContents, 0, contents.size - headIndex)
        Array.copy(contents, 0, newContents, contents.size - headIndex, tailIndex)
      }
      tailIndex = size
      headIndex = 0
      contents = newContents
      this
    }

    def append(value: A): RingBuffer[A] = {
      if (size == (capacity - 1)) {
        grow
      }
      contents(tailIndex) = value
      tailIndex = (tailIndex + 1) % capacity
      this
    }

    def prepend(value: A): RingBuffer[A] = {
      if (size == capacity) {
        grow
      }
      headIndex = (headIndex + capacity - 1) % capacity
      contents(headIndex) = value
      this
    }
    def appendAll(values: Iterable[A]): RingBuffer[A] = {
      values.foreach(append) // COULD actually grow once
      this
    }
    def prependAll(values: Iterable[A]): RingBuffer[A] = {
      values.foreach(prepend) // COULD actually grow once
      this
    }

    override def head: A = contents(headIndex)
    override def last: A = contents((tailIndex + capacity - 1) % capacity)

    def removeHead(): A = {
      if (nonEmpty) {
        val r = contents(headIndex)
        headIndex = (headIndex + 1) % capacity
        r
      } else {
        throw new IllegalStateException("container is empty")
      }
    }

    def removeLast(): A = {
      if (nonEmpty) {
        tailIndex = (tailIndex + capacity - 1) % capacity
        val r = contents(tailIndex)
        r
      } else {
        throw new IllegalStateException("container is empty")
      }
    }

    def iterator: Iterator[A] = (0 until size).map(i ⇒ contents(i % capacity)).iterator

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

  private var preTaken: ByteString = ByteString.empty
  private var buffer: ByteString = ByteString.empty
  private val nextBuffers: JsonObjectParser.RingBuffer[ByteString] = new JsonObjectParser.RingBuffer
  private var pos = 0 // latest position of pointer while scanning for json object end
  private var trimFront = 0 // number of chars to drop from the front of the bytestring before emitting (skip whitespace etc)
  private var completedObject = false
  private var state: ParserState = ParserState.InitialState

  private var stateAfterBackslash: ParserState = _
  private var stateAfterLeafValue: ParserState = _
  private var stateAfterRootValue: ParserState = _

  private val containerStack: JsonObjectParser.RingBuffer[ParserState] = new JsonObjectParser.RingBuffer[ParserState]

  @inline private def startObject(): Unit = {
    trimFront = pos
  }

  private def objectDone(): Unit = {
    completedObject = true
  }

  @inline private def switchToState[S <: ParserState](state: S): S = {
    this.state = state
    state
  }

  private implicit def self: JsonObjectParser = this
  /**
   * Appends input ByteString to internal byte string buffer.
   * Use [[poll]] to extract contained JSON objects.
   */
  def offer(input: ByteString): Unit = {
    input match {
      case _ if input.isEmpty ⇒
        ()
      case compact: CompactByteString ⇒
        nextBuffers.append(compact)
      case _ ⇒
        val fragments = input.asByteBuffers
          .map(buf ⇒ CompactByteString.apply(buf))
          .filter(_.nonEmpty)
        nextBuffers.appendAll(fragments)
    }
  }

  def isEmpty: Boolean = buffer.isEmpty && nextBuffers.isEmpty

  /**
   * Attempt to locate next complete JSON object in buffered ByteString and returns `Some(it)` if found.
   * May throw a [[akka.stream.scaladsl.Framing.FramingException]] if the contained JSON is invalid or max object size is exceeded.
   */
  def poll(): Option[ByteString] = {
    if (buffer.isEmpty && !(takeNextBufferIfReady(): @noinline)) {
      None
    } else {
      val foundObject = seekObject()

      if (foundObject && (pos > 0)) {
        emitItem()
      } else {
        None
      }
    }
  }

  /**
   * Keep around a small moving average of object size in order to keep the scope of ByteString compaction to a
   * reasonable quantity
   */

  private def emitItem(): Option[ByteString] = {
    val emit = buffer.drop(trimFront).take(pos - trimFront)
    val buf = buffer.drop(pos)

    /* NOTE that we NEVER compact any of the ByteStrings we manipulate, effectively working in a zero-copy way over the
    * raw byte buffers. */

    val ready = preTaken ++ emit

    preTaken = ByteString.empty
    buffer = buf
    pos = 0
    trimFront = 0

    val result = if (ready.isEmpty)
      None
    else Some(ready)

    result
  }

  @noinline private def takeNextBufferInternal(): Boolean = {
    val headNext = nextBuffers.removeHead()

    val taken = headNext

    if (state.inObject) {
      val firstBit = buffer.drop(trimFront).take(pos - trimFront)
      this.preTaken ++= firstBit
    } else {
      /* we don't need to keep anything from the current buffer: it's all going to be trimmed away. */
    }
    pos = 0
    trimFront = 0
    buffer = taken

    buffer.nonEmpty

  }

  private def takeNextBufferIfReady(): Boolean = {
    if (nextBuffers.nonEmpty) {
      takeNextBufferInternal()
    } else {
      false
    }
  }

  /** @return true if an entire valid JSON object was found, false otherwise */
  @tailrec
  private def seekObject(): Boolean = {
    completedObject = false

    val maxSeekPos = Math.min(buffer.length, maximumObjectLength)

    while (state.seekNextEvent(maxSeekPos)) {
      // keep going
    }

    if ((preTaken.length + pos) >= maximumObjectLength)
      throw new FramingException(s"""JSON element exceeded maximumObjectLength ($maximumObjectLength bytes)!""")

    if (completedObject) {
      true
    } else {
      /* we haven't yet found a completedObject, but we might have additional data lying around */

      if ((pos == maxSeekPos) && nextBuffers.nonEmpty) {
        /* reached the end of the buffer without having a complete object to return, but there is more around… Loop */
        (takeNextBufferIfReady(): @noinline) && seekObject()
      } else {
        /* nothing better for now */
        false
      }
    }
  }

  override def toString: String = s"JsonObjectParser(at ${state.debugState(state)})"
}
