/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.event.LoggingAdapter
import akka.parboiled2.CharPredicate
import akka.stream.Transformer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.http.model._
import akka.http.util._
import headers._

/**
 * INTERNAL API
 *
 * see: http://tools.ietf.org/html/rfc2046#section-5.1.1
 */
private[http] final class BodyPartParser(defaultContentType: ContentType,
                                         boundary: String,
                                         log: LoggingAdapter,
                                         settings: BodyPartParser.Settings = BodyPartParser.defaultSettings)
  extends Transformer[ByteString, BodyPartParser.Output] {
  import BodyPartParser._
  import settings._

  require(boundary.nonEmpty, "'boundary' parameter of multipart Content-Type must be non-empty")
  require(boundary.charAt(boundary.length - 1) != ' ', "'boundary' parameter of multipart Content-Type must not end with a space char")
  require(boundaryCharNoSpace matchesAll boundary,
    s"'boundary' parameter of multipart Content-Type contains illegal character '${boundaryCharNoSpace.firstMismatch(boundary).get}'")

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

  private[this] val headerParser = HttpHeaderParser(settings, warnOnIllegalHeader) // TODO: prevent re-priming header parser from scratch
  private[this] val result = new ListBuffer[Output] // transformer op is currently optimized for LinearSeqs
  private[this] var state: ByteString ⇒ StateResult = tryParseInitialBoundary
  private[this] var terminated = false

  override def isComplete = terminated

  def warnOnIllegalHeader(errorInfo: ErrorInfo): Unit =
    if (illegalHeaderWarnings) log.warning(errorInfo.withSummaryPrepended("Illegal multipart header").formatPretty)

  def onNext(input: ByteString): List[Output] = {
    result.clear()
    try state(input)
    catch {
      case e: ParsingException    ⇒ fail(e.info)
      case NotEnoughDataException ⇒ throw new IllegalStateException(NotEnoughDataException) // we are missing a try/catch{continue} wrapper somewhere
    }
    result.toList
  }

  def tryParseInitialBoundary(input: ByteString): StateResult = {
    // we don't use boyerMoore here because we are testing for the boundary *without* a
    // preceding CRLF and at a known location (the very beginning of the entity)
    try {
      @tailrec def rec(ix: Int): StateResult =
        if (ix < needle.length) {
          if (byteAt(input, ix - 2) == needle(ix)) rec(ix + 1)
          else parsePreamble(input, 0)
        } else {
          if (crlf(input, ix - 2)) parseHeaderLines(input, ix)
          else if (doubleDash(input, ix - 2)) terminate()
          else parsePreamble(input, 0)
        }
      rec(2)
    } catch {
      case NotEnoughDataException ⇒ continue((input, _) ⇒ tryParseInitialBoundary(input))
    }
  }

  def parsePreamble(input: ByteString, offset: Int): StateResult = {
    try {
      @tailrec def rec(index: Int): StateResult = {
        val needleEnd = boyerMoore.nextIndex(input, index) + needle.length
        if (crlf(input, needleEnd)) parseHeaderLines(input, needleEnd + 2)
        else if (doubleDash(input, needleEnd)) terminate()
        else rec(needleEnd)
      }
      rec(offset)
    } catch {
      case NotEnoughDataException ⇒ continue(input.takeRight(needle.length), 0)(parsePreamble)
    }
  }

  @tailrec def parseHeaderLines(input: ByteString, lineStart: Int, headers: List[HttpHeader] = Nil,
                                headerCount: Int = 0, cth: Option[`Content-Type`] = None): StateResult = {
    var lineEnd = 0
    val resultHeader =
      try {
        lineEnd = headerParser.parseHeaderLine(input, lineStart)()
        headerParser.resultHeader
      } catch {
        case NotEnoughDataException ⇒ null
      }
    resultHeader match {
      case null ⇒ continue(input, lineStart)(parseHeaderLinesAux(headers, headerCount, cth))

      case HttpHeaderParser.EmptyHeader ⇒
        val contentType = cth match {
          case Some(x) ⇒ x.contentType
          case None    ⇒ defaultContentType
        }
        parseEntity(headers, contentType)(input, lineEnd)

      case h: `Content-Type` ⇒
        if (cth.isEmpty) parseHeaderLines(input, lineEnd, headers, headerCount + 1, Some(h))
        else if (cth.get == h) parseHeaderLines(input, lineEnd, headers, headerCount, cth)
        else fail("multipart part must not contain more than one Content-Type header")

      case h if headerCount < maxHeaderCount ⇒ parseHeaderLines(input, lineEnd, h :: headers, headerCount + 1, cth)

      case _                                 ⇒ fail(s"multipart part contains more than the configured limit of $maxHeaderCount headers")
    }
  }

  // work-around for compiler complaining about non-tail-recursion if we inline this method
  def parseHeaderLinesAux(headers: List[HttpHeader], headerCount: Int,
                          cth: Option[`Content-Type`])(input: ByteString, lineStart: Int): StateResult =
    parseHeaderLines(input, lineStart, headers, headerCount, cth)

  def parseEntity(headers: List[HttpHeader], contentType: ContentType,
                  emitPartChunk: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = {
                    (headers, ct, bytes) ⇒
                      emit(BodyPartStart(headers, entityParts ⇒ HttpEntity.IndefiniteLength(ct,
                        entityParts.collect { case EntityPart(data) ⇒ data })))
                      emit(bytes)
                  },
                  emitFinalPartChunk: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = {
                    (headers, ct, bytes) ⇒ emit(BodyPartStart(headers, _ ⇒ HttpEntity.Strict(ct, bytes)))
                  })(input: ByteString, offset: Int): StateResult =
    try {
      @tailrec def rec(index: Int): StateResult = {
        val currentPartEnd = boyerMoore.nextIndex(input, index)
        def emitFinalChunk() = emitFinalPartChunk(headers, contentType, input.slice(offset, currentPartEnd))
        val needleEnd = currentPartEnd + needle.length
        if (crlf(input, needleEnd)) {
          emitFinalChunk()
          parseHeaderLines(input, needleEnd + 2)
        } else if (doubleDash(input, needleEnd)) {
          emitFinalChunk()
          terminate()
        } else rec(needleEnd)
      }
      rec(offset)
    } catch {
      case NotEnoughDataException ⇒
        // we cannot emit all input bytes since the end of the input might be the start of the next boundary
        val emitEnd = math.max(input.length - needle.length, offset)
        emitPartChunk(headers, contentType, input.slice(offset, emitEnd))
        val simpleEmit: (List[HttpHeader], ContentType, ByteString) ⇒ Unit = (_, _, bytes) ⇒ emit(bytes)
        continue(input drop emitEnd, 0)(parseEntity(null, null, simpleEmit, simpleEmit))
    }

  def emit(bytes: ByteString): Unit = if (bytes.nonEmpty) emit(EntityPart(bytes))

  def emit(output: Output): Unit = result += output

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
    terminate()
  }

  def terminate(): StateResult = {
    terminated = true
    done()
  }

  def done(): StateResult = null // StateResult is a phantom type

  def crlf(input: ByteString, offset: Int): Boolean =
    byteChar(input, offset) == '\r' && byteChar(input, offset + 1) == '\n'

  def doubleDash(input: ByteString, offset: Int): Boolean =
    byteChar(input, offset) == '-' && byteChar(input, offset + 1) == '-'
}

private[http] object BodyPartParser {
  // http://tools.ietf.org/html/rfc2046#section-5.1.1
  val boundaryCharNoSpace = CharPredicate.Digit ++ CharPredicate.Alpha ++ "'()+_,-./:=?"

  sealed trait Output
  final case class BodyPartStart(headers: List[HttpHeader], createEntity: Source[Output] ⇒ BodyPartEntity) extends Output
  final case class EntityPart(data: ByteString) extends Output
  final case class ParseError(info: ErrorInfo) extends Output

  final case class Settings(
    maxHeaderNameLength: Int,
    maxHeaderValueLength: Int,
    maxHeaderCount: Int,
    illegalHeaderWarnings: Boolean,
    headerValueCacheLimit: Int) extends HttpHeaderParser.Settings {
    require(maxHeaderNameLength > 0, "maxHeaderNameLength must be > 0")
    require(maxHeaderValueLength > 0, "maxHeaderValueLength must be > 0")
    require(maxHeaderCount > 0, "maxHeaderCount must be > 0")
    require(headerValueCacheLimit >= 0, "headerValueCacheLimit must be >= 0")
    def headerValueCacheLimit(headerName: String) = headerValueCacheLimit
  }

  // TODO: load from config
  val defaultSettings = Settings(
    maxHeaderNameLength = 64,
    maxHeaderValueLength = 8192,
    maxHeaderCount = 64,
    illegalHeaderWarnings = true,
    headerValueCacheLimit = 8)
}

