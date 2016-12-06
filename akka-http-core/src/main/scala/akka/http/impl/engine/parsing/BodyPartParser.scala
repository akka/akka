/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import akka.NotUsed

import scala.annotation.tailrec
import akka.event.LoggingAdapter
import akka.parboiled2.CharPredicate
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import akka.http.scaladsl.model._
import akka.http.impl.util._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import headers._

import scala.collection.mutable.ListBuffer
import akka.stream.impl.fusing.SubSource

/**
 * INTERNAL API
 *
 * see: http://tools.ietf.org/html/rfc2046#section-5.1.1
 */
private[http] final class BodyPartParser(
  defaultContentType: ContentType,
  boundary:           String,
  log:                LoggingAdapter,
  settings:           BodyPartParser.Settings)
  extends GraphStage[FlowShape[ByteString, BodyPartParser.Output]] {
  import BodyPartParser._
  import settings._

  require(boundary.nonEmpty, "'boundary' parameter of multipart Content-Type must be non-empty")
  require(boundary.charAt(boundary.length - 1) != ' ', "'boundary' parameter of multipart Content-Type must not end with a space char")
  require(
    boundaryChar matchesAll boundary,
    s"'boundary' parameter of multipart Content-Type contains illegal character '${boundaryChar.firstMismatch(boundary).get}'")

  sealed trait StateResult // phantom type for ensuring soundness of our parsing method setup

  private[this] val needle: Array[Byte] = {
    val array = new Array[Byte](boundary.length + 4)
    array(0) = '\r'.toByte
    array(1) = '\n'.toByte
    array(2) = '-'.toByte
    array(3) = '-'.toByte
    boundary.getAsciiBytes(array, 4)
    array
  }

  // we use the Boyer-Moore string search algorithm for finding the boundaries in the multipart entity,
  // TODO: evaluate whether an upgrade to the more efficient FJS is worth the implementation cost
  // see: http://www.cgjennings.ca/fjs/ and http://ijes.info/4/1/42544103.pdf
  private[this] val boyerMoore = new BoyerMoore(needle)

  // TODO: prevent re-priming header parser from scratch
  private[this] val headerParser = HttpHeaderParser(settings, log) { errorInfo ⇒
    if (illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal multipart header").formatPretty)
  }

  val in = Inlet[ByteString]("BodyPartParser.in")
  val out = Outlet[BodyPartParser.Output]("BodyPartParser.out")

  override val shape = FlowShape(in, out)

  override def createLogic(attributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var output = collection.immutable.Queue.empty[Output] // FIXME this probably is too wasteful
      private var state: ByteString ⇒ StateResult = tryParseInitialBoundary
      private var shouldTerminate = false

      override def onPush(): Unit = {
        if (!shouldTerminate) {
          val elem = grab(in)
          try run(elem)
          catch {
            case e: ParsingException ⇒ fail(e.info)
            case NotEnoughDataException ⇒
              // we are missing a try/catch{continue} wrapper somewhere
              throw new IllegalStateException("unexpected NotEnoughDataException", NotEnoughDataException)
          }
          if (output.nonEmpty) push(out, dequeue())
          else if (!shouldTerminate) pull(in)
          else completeStage()
        } else completeStage()
      }

      override def onPull(): Unit = {
        if (output.nonEmpty) push(out, dequeue())
        else if (isClosed(in)) {
          if (!shouldTerminate) push(out, ParseError(ErrorInfo("Unexpected end of multipart entity")))
          completeStage()
        } else pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (isAvailable(out)) onPull()
      }

      // hacky trampolining support: parsing states may call `trampoline(continuation)` to loop safely
      def run(data: ByteString): Unit = {
        @tailrec def loop(): Unit =
          trampoline match {
            case null ⇒
            case f ⇒
              trampoline = null
              f()
              loop()
          }

        state(data)
        loop()
      }
      private var trampoline: () ⇒ StateResult = null
      def trampoline(continue: ⇒ StateResult): StateResult = {
        require(trampoline eq null)
        trampoline = continue _
        done()
      }

      setHandlers(in, out, this)

      def warnOnIllegalHeader(errorInfo: ErrorInfo): Unit =
        if (illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal multipart header").formatPretty)

      def tryParseInitialBoundary(input: ByteString): StateResult =
        // we don't use boyerMoore here because we are testing for the boundary *without* a
        // preceding CRLF and at a known location (the very beginning of the entity)
        try {
          if (boundary(input, 0)) {
            val ix = boundaryLength
            if (crlf(input, ix)) parseHeaderLines(input, ix + 2)
            else if (doubleDash(input, ix)) setShouldTerminate()
            else parsePreamble(input, 0)
          } else parsePreamble(input, 0)
        } catch {
          case NotEnoughDataException ⇒ continue(input, 0)((newInput, _) ⇒ tryParseInitialBoundary(newInput))
        }

      def parsePreamble(input: ByteString, offset: Int): StateResult =
        try {
          @tailrec def rec(index: Int): StateResult = {
            val needleEnd = boyerMoore.nextIndex(input, index) + needle.length
            if (crlf(input, needleEnd)) parseHeaderLines(input, needleEnd + 2)
            else if (doubleDash(input, needleEnd)) setShouldTerminate()
            else rec(needleEnd)
          }
          rec(offset)
        } catch {
          case NotEnoughDataException ⇒ continue(input.takeRight(needle.length + 2), 0)(parsePreamble)
        }

      @tailrec def parseHeaderLines(input: ByteString, lineStart: Int, headers: ListBuffer[HttpHeader] = ListBuffer[HttpHeader](),
                                    headerCount: Int = 0, cth: Option[`Content-Type`] = None): StateResult = {
        def contentType =
          cth match {
            case Some(x) ⇒ x.contentType
            case None    ⇒ defaultContentType
          }

        var lineEnd = 0
        val resultHeader =
          try {
            if (!boundary(input, lineStart)) {
              lineEnd = headerParser.parseHeaderLine(input, lineStart)()
              headerParser.resultHeader
            } else BoundaryHeader
          } catch {
            case NotEnoughDataException ⇒ null
          }
        resultHeader match {
          case null ⇒ continue(input, lineStart)(parseHeaderLinesAux(headers, headerCount, cth))

          case BoundaryHeader ⇒
            emit(BodyPartStart(headers.toList, _ ⇒ HttpEntity.empty(contentType)))
            val ix = lineStart + boundaryLength
            if (crlf(input, ix)) parseHeaderLines(input, ix + 2)
            else if (doubleDash(input, ix)) setShouldTerminate()
            else fail("Illegal multipart boundary in message content")

          case EmptyHeader ⇒ parseEntity(headers.toList, contentType)(input, lineEnd)

          case h: `Content-Type` ⇒
            if (cth.isEmpty) parseHeaderLines(input, lineEnd, headers, headerCount + 1, Some(h))
            else if (cth.get == h) parseHeaderLines(input, lineEnd, headers, headerCount, cth)
            else fail("multipart part must not contain more than one Content-Type header")

          case h if headerCount < maxHeaderCount ⇒
            parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, cth)

          case _ ⇒ fail(s"multipart part contains more than the configured limit of $maxHeaderCount headers")
        }
      }

      // work-around for compiler complaining about non-tail-recursion if we inline this method
      def parseHeaderLinesAux(headers: ListBuffer[HttpHeader], headerCount: Int,
                              cth: Option[`Content-Type`])(input: ByteString, lineStart: Int): StateResult =
        parseHeaderLines(input, lineStart, headers, headerCount, cth)

      def parseEntity(headers: List[HttpHeader], contentType: ContentType,
                      emitPartChunk: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = {
                        (headers, ct, bytes) ⇒
                          emit(BodyPartStart(headers, entityParts ⇒ HttpEntity.IndefiniteLength(
                            ct,
                            entityParts.collect { case EntityPart(data) ⇒ data })))
                          emit(bytes)
                      },
                      emitFinalPartChunk: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = {
                        (headers, ct, bytes) ⇒
                          emit(BodyPartStart(headers, { rest ⇒
                            SubSource.kill(rest)
                            HttpEntity.Strict(ct, bytes)
                          }))
                      })(input: ByteString, offset: Int): StateResult =
        try {
          @tailrec def rec(index: Int): StateResult = {
            val currentPartEnd = boyerMoore.nextIndex(input, index)
            def emitFinalChunk() = emitFinalPartChunk(headers, contentType, input.slice(offset, currentPartEnd))
            val needleEnd = currentPartEnd + needle.length
            if (crlf(input, needleEnd)) {
              emitFinalChunk()
              // Need to trampoline here, otherwise we have a mutual tail recursion between parseHeaderLines and
              // parseEntity that is not tail-call optimized away and may lead to stack overflows on big chunks of data
              // containing many parts.
              trampoline(parseHeaderLines(input, needleEnd + 2))
            } else if (doubleDash(input, needleEnd)) {
              emitFinalChunk()
              setShouldTerminate()
            } else rec(needleEnd)
          }
          rec(offset)
        } catch {
          case NotEnoughDataException ⇒
            // we cannot emit all input bytes since the end of the input might be the start of the next boundary
            val emitEnd = input.length - needle.length - 2
            if (emitEnd > offset) {
              emitPartChunk(headers, contentType, input.slice(offset, emitEnd))
              val simpleEmit: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = (_, _, bytes) ⇒ emit(bytes)
              continue(input drop emitEnd, 0)(parseEntity(null, null, simpleEmit, simpleEmit))
            } else continue(input, offset)(parseEntity(headers, contentType, emitPartChunk, emitFinalPartChunk))
        }

      def emit(bytes: ByteString): Unit = if (bytes.nonEmpty) emit(EntityPart(bytes))

      def emit(element: Output): Unit = output = output.enqueue(element)

      def dequeue(): Output = {
        val head = output.head
        output = output.tail
        head
      }

      def continue(input: ByteString, offset: Int)(next: (ByteString, Int) ⇒ StateResult): StateResult = {
        state =
          math.signum(offset - input.length) match {
            case -1 ⇒ more ⇒ next(input ++ more, offset)
            case 0 ⇒ next(_, 0)
            case 1 ⇒ throw new IllegalStateException
          }
        done()
      }

      def continue(next: (ByteString, Int) ⇒ StateResult): StateResult = {
        state = next(_, 0)
        done()
      }

      def fail(summary: String): StateResult = fail(ErrorInfo(summary))

      def fail(info: ErrorInfo): StateResult = {
        emit(ParseError(info))
        setShouldTerminate()
      }

      def setShouldTerminate(): StateResult = {
        shouldTerminate = true
        done()
      }

      def done(): StateResult = null // StateResult is a phantom type

      // the length of the needle without the preceding CRLF
      def boundaryLength = needle.length - 2

      @tailrec def boundary(input: ByteString, offset: Int, ix: Int = 2): Boolean =
        (ix == needle.length) || (byteAt(input, offset + ix - 2) == needle(ix)) && boundary(input, offset, ix + 1)

      def crlf(input: ByteString, offset: Int): Boolean =
        byteChar(input, offset) == '\r' && byteChar(input, offset + 1) == '\n'

      def doubleDash(input: ByteString, offset: Int): Boolean =
        byteChar(input, offset) == '-' && byteChar(input, offset + 1) == '-'
    }
}

private[http] object BodyPartParser {
  // http://tools.ietf.org/html/rfc2046#section-5.1.1
  val boundaryChar = CharPredicate.Digit ++ CharPredicate.Alpha ++ "'()+_,-./:=? "

  private object BoundaryHeader extends HttpHeader {
    def renderInRequests = false
    def renderInResponses = false
    def name = ""
    def lowercaseName = ""
    def value = ""
    def render[R <: Rendering](r: R): r.type = r
    override def toString = "BoundaryHeader"
  }

  sealed trait Output
  sealed trait PartStart extends Output
  final case class BodyPartStart(headers: List[HttpHeader], createEntity: Source[Output, NotUsed] ⇒ BodyPartEntity) extends PartStart
  final case class EntityPart(data: ByteString) extends Output
  final case class ParseError(info: ErrorInfo) extends PartStart

  abstract class Settings extends HttpHeaderParser.Settings {
    def maxHeaderCount: Int
    def illegalHeaderWarnings: Boolean
    def defaultHeaderValueCacheLimit: Int
  }
}
