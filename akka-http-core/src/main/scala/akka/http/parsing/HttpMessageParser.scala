/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.collection.immutable
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.stream.{ FlowMaterializer, Transformer }
import akka.http.model.parser.CharacterClasses
import akka.http.model._
import headers._
import HttpProtocols._
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Flow

/**
 * INTERNAL API
 */
private[http] abstract class HttpMessageParser[Output >: ParserOutput.MessageOutput <: ParserOutput](val settings: ParserSettings,
                                                                                                     val headerParser: HttpHeaderParser)
  extends Transformer[ByteString, Output] {

  sealed trait StateResult // phantom type for ensuring soundness of our parsing method setup

  private[this] val result = new ListBuffer[Output] // transformer op is currently optimized for LinearSeqs
  private[this] var state: ByteString ⇒ StateResult = startNewMessage(_, 0)
  private[this] var protocol: HttpProtocol = `HTTP/1.1`
  private[this] var terminated = false
  override def isComplete = terminated

  def onNext(input: ByteString): immutable.Seq[Output] = {
    result.clear()
    try state(input)
    catch {
      case e: ParsingException    ⇒ fail(e.status, e.info)
      case NotEnoughDataException ⇒ throw new IllegalStateException // we are missing a try/catch{continue} wrapper somewhere
    }
    result.toList
  }

  def startNewMessage(input: ByteString, offset: Int): StateResult = {
    def _startNewMessage(input: ByteString, offset: Int): StateResult =
      try parseMessage(input, offset)
      catch { case NotEnoughDataException ⇒ continue(input, offset)(_startNewMessage) }

    _startNewMessage(input, offset)
  }

  def parseMessage(input: ByteString, offset: Int): StateResult

  def parseProtocol(input: ByteString, cursor: Int): Int = {
    def c(ix: Int) = byteChar(input, cursor + ix)
    if (c(0) == 'H' && c(1) == 'T' && c(2) == 'T' && c(3) == 'P' && c(4) == '/' && c(5) == '1' && c(6) == '.') {
      protocol = c(7) match {
        case '0' ⇒ `HTTP/1.0`
        case '1' ⇒ `HTTP/1.1`
        case _   ⇒ badProtocol
      }
      cursor + 8
    } else badProtocol
  }

  def badProtocol: Nothing

  @tailrec final def parseHeaderLines(input: ByteString, lineStart: Int, headers: List[HttpHeader] = Nil,
                                      headerCount: Int = 0, ch: Option[Connection] = None,
                                      clh: Option[`Content-Length`] = None, cth: Option[`Content-Type`] = None,
                                      teh: Option[`Transfer-Encoding`] = None, hh: Boolean = false): StateResult = {
    var lineEnd = 0
    val resultHeader =
      try {
        lineEnd = headerParser.parseHeaderLine(input, lineStart)()
        headerParser.resultHeader
      } catch {
        case NotEnoughDataException ⇒ null
      }
    resultHeader match {
      case null ⇒ continue(input, lineStart)(parseHeaderLinesAux(headers, headerCount, ch, clh, cth, teh, hh))

      case HttpHeaderParser.EmptyHeader ⇒
        val close = HttpMessage.connectionCloseExpected(protocol, ch)
        parseEntity(headers, protocol, input, lineEnd, clh, cth, teh, hh, close)

      case h: Connection ⇒
        parseHeaderLines(input, lineEnd, h :: headers, headerCount + 1, Some(h), clh, cth, teh, hh)

      case h: `Content-Length` ⇒
        if (clh.isEmpty) parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, Some(h), cth, teh, hh)
        else fail("HTTP message must not contain more than one Content-Length header")

      case h: `Content-Type` ⇒
        if (cth.isEmpty) parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, Some(h), teh, hh)
        else if (cth.get == h) parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, teh, hh)
        else fail("HTTP message must not contain more than one Content-Type header")

      case h: `Transfer-Encoding` ⇒
        parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, cth, Some(h), hh)

      case h if headerCount < settings.maxHeaderCount ⇒
        parseHeaderLines(input, lineEnd, h :: headers, headerCount + 1, ch, clh, cth, teh, hh || h.isInstanceOf[Host])

      case _ ⇒ fail(s"HTTP message contains more than the configured limit of ${settings.maxHeaderCount} headers")
    }
  }

  // work-around for compiler complaining about non-tail-recursion if we inline this method
  def parseHeaderLinesAux(headers: List[HttpHeader], headerCount: Int, ch: Option[Connection],
                          clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                          hh: Boolean)(input: ByteString, lineStart: Int): StateResult =
    parseHeaderLines(input, lineStart, headers, headerCount, ch, clh, cth, teh, hh)

  def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                  hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult

  def parseFixedLengthBody(remainingBodyBytes: Long,
                           isLastMessage: Boolean)(input: ByteString, bodyStart: Int): StateResult = {
    val remainingInputBytes = input.length - bodyStart
    if (remainingInputBytes > 0) {
      if (remainingInputBytes < remainingBodyBytes) {
        emit(ParserOutput.EntityPart(input drop bodyStart))
        continue(parseFixedLengthBody(remainingBodyBytes - remainingInputBytes, isLastMessage))
      } else {
        val offset = bodyStart + remainingBodyBytes.toInt
        emit(ParserOutput.EntityPart(input.slice(bodyStart, offset)))
        if (isLastMessage) terminate()
        else startNewMessage(input, offset)
      }
    } else continue(input, bodyStart)(parseFixedLengthBody(remainingBodyBytes, isLastMessage))
  }

  def parseChunk(input: ByteString, offset: Int, isLastMessage: Boolean): StateResult = {
    @tailrec def parseTrailer(extension: String, lineStart: Int, headers: List[HttpHeader] = Nil,
                              headerCount: Int = 0): StateResult = {
      val lineEnd = headerParser.parseHeaderLine(input, lineStart)()
      headerParser.resultHeader match {
        case HttpHeaderParser.EmptyHeader ⇒
          val lastChunk =
            if (extension.isEmpty && headers.isEmpty) HttpEntity.LastChunk else HttpEntity.LastChunk(extension, headers)
          emit(ParserOutput.EntityChunk(lastChunk))
          if (isLastMessage) terminate()
          else startNewMessage(input, lineEnd)
        case header if headerCount < settings.maxHeaderCount ⇒
          parseTrailer(extension, lineEnd, header :: headers, headerCount + 1)
        case _ ⇒ fail(s"Chunk trailer contains more than the configured limit of ${settings.maxHeaderCount} headers")
      }
    }

    def parseChunkBody(chunkSize: Int, extension: String, cursor: Int): StateResult =
      if (chunkSize > 0) {
        val chunkBodyEnd = cursor + chunkSize
        def result(terminatorLen: Int) = {
          emit(ParserOutput.EntityChunk(HttpEntity.Chunk(input.slice(cursor, chunkBodyEnd), extension)))
          parseChunk(input, chunkBodyEnd + terminatorLen, isLastMessage)
        }
        byteChar(input, chunkBodyEnd) match {
          case '\r' if byteChar(input, chunkBodyEnd + 1) == '\n' ⇒ result(2)
          case '\n' ⇒ result(1)
          case x ⇒ fail("Illegal chunk termination")
        }
      } else parseTrailer(extension, cursor)

    @tailrec def parseChunkExtensions(chunkSize: Int, cursor: Int)(startIx: Int = cursor): StateResult =
      if (cursor - startIx <= settings.maxChunkExtLength) {
        def extension = asciiString(input, startIx, cursor)
        byteChar(input, cursor) match {
          case '\r' if byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 2)
          case '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 1)
          case _ ⇒ parseChunkExtensions(chunkSize, cursor + 1)(startIx)
        }
      } else fail(s"HTTP chunk extension length exceeds configured limit of ${settings.maxChunkExtLength} characters")

    @tailrec def parseSize(cursor: Int, size: Long): StateResult =
      if (size <= settings.maxChunkSize) {
        byteChar(input, cursor) match {
          case c if CharacterClasses.HEXDIG(c) ⇒ parseSize(cursor + 1, size * 16 + CharUtils.hexValue(c))
          case ';' if cursor > offset ⇒ parseChunkExtensions(size.toInt, cursor + 1)()
          case '\r' if cursor > offset && byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(size.toInt, "", cursor + 2)
          case c ⇒ fail(s"Illegal character '${escape(c)}' in chunk start")
        }
      } else fail(s"HTTP chunk size exceeds the configured limit of ${settings.maxChunkSize} bytes")

    try parseSize(offset, 0)
    catch {
      case NotEnoughDataException ⇒ continue(input, offset)(parseChunk(_, _, isLastMessage))
    }
  }

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

  def fail(summary: String): StateResult = fail(summary, "")
  def fail(summary: String, detail: String): StateResult = fail(StatusCodes.BadRequest, summary, detail)
  def fail(status: StatusCode): StateResult = fail(status, status.defaultMessage)
  def fail(status: StatusCode, summary: String, detail: String = ""): StateResult = fail(status, ErrorInfo(summary, detail))
  def fail(status: StatusCode, info: ErrorInfo): StateResult = {
    emit(ParserOutput.ParseError(status, info))
    terminate()
  }

  def terminate(): StateResult = {
    terminated = true
    done()
  }

  def done(): StateResult = null // StateResult is a phantom type

  def contentType(cth: Option[`Content-Type`]) = cth match {
    case Some(x) ⇒ x.contentType
    case None    ⇒ ContentTypes.`application/octet-stream`
  }

  def emptyEntity(cth: Option[`Content-Type`])(entityParts: Any): HttpEntity.Regular =
    if (cth.isDefined) HttpEntity.empty(cth.get.contentType) else HttpEntity.Empty

  def strictEntity(cth: Option[`Content-Type`], input: ByteString, bodyStart: Int,
                   contentLength: Int)(entityParts: Any): HttpEntity.Regular =
    HttpEntity.Strict(contentType(cth), input.slice(bodyStart, bodyStart + contentLength))

  def defaultEntity(cth: Option[`Content-Type`], contentLength: Long,
                    materializer: FlowMaterializer)(entityParts: Publisher[_ <: ParserOutput]): HttpEntity.Regular = {
    val data = Flow(entityParts).collect { case ParserOutput.EntityPart(bytes) ⇒ bytes }.toPublisher(materializer)
    HttpEntity.Default(contentType(cth), contentLength, data)
  }

  def chunkedEntity(cth: Option[`Content-Type`],
                    materializer: FlowMaterializer)(entityChunks: Publisher[_ <: ParserOutput]): HttpEntity.Regular = {
    val chunks = Flow(entityChunks).collect { case ParserOutput.EntityChunk(chunk) ⇒ chunk }.toPublisher(materializer)
    HttpEntity.Chunked(contentType(cth), chunks)
  }
}