/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import javax.net.ssl.SSLSession

import akka.stream.TLSProtocol._

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.parboiled2.CharUtils
import akka.util.ByteString
import akka.stream.stage._
import akka.http.impl.model.parser.CharacterClasses
import akka.http.scaladsl.settings.ParserSettings
import akka.http.scaladsl.model._
import headers._
import HttpProtocols._
import ParserOutput._

/**
 * INTERNAL API
 */
private[http] abstract class HttpMessageParser[Output >: MessageOutput <: ParserOutput](val settings: ParserSettings,
                                                                                        val headerParser: HttpHeaderParser) { self ⇒
  import HttpMessageParser._
  import settings._

  private[this] val result = new ListBuffer[Output]
  private[this] var state: ByteString ⇒ StateResult = startNewMessage(_, 0)
  private[this] var protocol: HttpProtocol = `HTTP/1.1`
  private[this] var completionHandling: CompletionHandling = CompletionOk
  private[this] var terminated = false

  private[this] var lastSession: SSLSession = null // used to prevent having to recreate header on each message
  private[this] var tlsSessionInfoHeader: `Tls-Session-Info` = null
  def initialHeaderBuffer: ListBuffer[HttpHeader] =
    if (settings.includeTlsSessionInfoHeader && tlsSessionInfoHeader != null) ListBuffer(tlsSessionInfoHeader)
    else ListBuffer()

  def isTerminated = terminated

  val stage: PushPullStage[SessionBytes, Output] =
    new PushPullStage[SessionBytes, Output] {
      def onPush(input: SessionBytes, ctx: Context[Output]) = handleParserOutput(self.parseSessionBytes(input), ctx)
      def onPull(ctx: Context[Output]) = handleParserOutput(self.onPull(), ctx)
      private def handleParserOutput(output: Output, ctx: Context[Output]): SyncDirective =
        output match {
          case StreamEnd    ⇒ ctx.finish()
          case NeedMoreData ⇒ ctx.pull()
          case x            ⇒ ctx.push(x)
        }
      override def onUpstreamFinish(ctx: Context[Output]): TerminationDirective =
        if (self.onUpstreamFinish()) ctx.finish() else ctx.absorbTermination()
    }

  final def parseSessionBytes(input: SessionBytes): Output = {
    if (input.session ne lastSession) {
      lastSession = input.session
      tlsSessionInfoHeader = `Tls-Session-Info`(input.session)
    }
    parseBytes(input.bytes)
  }
  final def parseBytes(input: ByteString): Output = {
    @tailrec def run(next: ByteString ⇒ StateResult): StateResult =
      (try next(input)
      catch {
        case e: ParsingException ⇒ failMessageStart(e.status, e.info)
        case NotEnoughDataException ⇒
          // we are missing a try/catch{continue} wrapper somewhere
          throw new IllegalStateException("unexpected NotEnoughDataException", NotEnoughDataException)
      }) match {
        case Trampoline(x) ⇒ run(x)
        case x             ⇒ x
      }

    if (result.nonEmpty) throw new IllegalStateException("Unexpected `onPush`")
    run(state)
    onPull()
  }

  final def onPull(): Output =
    if (result.nonEmpty) {
      val head = result.head
      result.remove(0) // faster than `ListBuffer::drop`
      head
    } else if (terminated) StreamEnd else NeedMoreData

  final def onUpstreamFinish(): Boolean = {
    completionHandling() match {
      case Some(x) ⇒ emit(x)
      case None    ⇒ // nothing to do
    }
    terminated = true
    result.isEmpty
  }

  protected final def startNewMessage(input: ByteString, offset: Int): StateResult = {
    if (offset < input.length) setCompletionHandling(CompletionIsMessageStartError)
    try parseMessage(input, offset)
    catch { case NotEnoughDataException ⇒ continue(input, offset)(startNewMessage) }
  }

  protected def parseMessage(input: ByteString, offset: Int): StateResult

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

  @tailrec final def parseHeaderLines(input: ByteString, lineStart: Int, headers: ListBuffer[HttpHeader] = initialHeaderBuffer,
                                      headerCount: Int = 0, ch: Option[Connection] = None,
                                      clh: Option[`Content-Length`] = None, cth: Option[`Content-Type`] = None,
                                      teh: Option[`Transfer-Encoding`] = None, e100c: Boolean = false,
                                      hh: Boolean = false): StateResult =
    if (headerCount < maxHeaderCount) {
      var lineEnd = 0
      val resultHeader =
        try {
          lineEnd = headerParser.parseHeaderLine(input, lineStart)()
          headerParser.resultHeader
        } catch {
          case NotEnoughDataException ⇒ null
        }
      resultHeader match {
        case null ⇒ continue(input, lineStart)(parseHeaderLinesAux(headers, headerCount, ch, clh, cth, teh, e100c, hh))

        case EmptyHeader ⇒
          val close = HttpMessage.connectionCloseExpected(protocol, ch)
          setCompletionHandling(CompletionIsEntityStreamError)
          parseEntity(headers.toList, protocol, input, lineEnd, clh, cth, teh, e100c, hh, close)

        case h: `Content-Length` ⇒ clh match {
          case None      ⇒ parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, Some(h), cth, teh, e100c, hh)
          case Some(`h`) ⇒ parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, teh, e100c, hh)
          case _         ⇒ failMessageStart("HTTP message must not contain more than one Content-Length header")
        }
        case h: `Content-Type` ⇒ cth match {
          case None      ⇒ parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, Some(h), teh, e100c, hh)
          case Some(`h`) ⇒ parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, teh, e100c, hh)
          case _         ⇒ failMessageStart("HTTP message must not contain more than one Content-Type header")
        }
        case h: `Transfer-Encoding` ⇒ teh match {
          case None    ⇒ parseHeaderLines(input, lineEnd, headers, headerCount + 1, ch, clh, cth, Some(h), e100c, hh)
          case Some(x) ⇒ parseHeaderLines(input, lineEnd, headers, headerCount, ch, clh, cth, Some(x append h.encodings), e100c, hh)
        }
        case h: Connection ⇒ ch match {
          case None    ⇒ parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, Some(h), clh, cth, teh, e100c, hh)
          case Some(x) ⇒ parseHeaderLines(input, lineEnd, headers, headerCount, Some(x append h.tokens), clh, cth, teh, e100c, hh)
        }
        case h: Host ⇒
          if (!hh) parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, teh, e100c, hh = true)
          else failMessageStart("HTTP message must not contain more than one Host header")

        case h: Expect ⇒ parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, teh, e100c = true, hh)

        case h         ⇒ parseHeaderLines(input, lineEnd, headers += h, headerCount + 1, ch, clh, cth, teh, e100c, hh)
      }
    } else failMessageStart(s"HTTP message contains more than the configured limit of $maxHeaderCount headers")

  // work-around for compiler complaining about non-tail-recursion if we inline this method
  def parseHeaderLinesAux(headers: ListBuffer[HttpHeader], headerCount: Int, ch: Option[Connection],
                          clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                          e100c: Boolean, hh: Boolean)(input: ByteString, lineStart: Int): StateResult =
    parseHeaderLines(input, lineStart, headers, headerCount, ch, clh, cth, teh, e100c, hh)

  def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                  expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult

  def parseFixedLengthBody(remainingBodyBytes: Long,
                           isLastMessage: Boolean)(input: ByteString, bodyStart: Int): StateResult = {
    val remainingInputBytes = input.length - bodyStart
    if (remainingInputBytes > 0) {
      if (remainingInputBytes < remainingBodyBytes) {
        emit(EntityPart(input.drop(bodyStart).compact))
        continue(parseFixedLengthBody(remainingBodyBytes - remainingInputBytes, isLastMessage))
      } else {
        val offset = bodyStart + remainingBodyBytes.toInt
        emit(EntityPart(input.slice(bodyStart, offset).compact))
        emit(MessageEnd)
        setCompletionHandling(CompletionOk)
        if (isLastMessage) terminate()
        else startNewMessage(input, offset)
      }
    } else continue(input, bodyStart)(parseFixedLengthBody(remainingBodyBytes, isLastMessage))
  }

  def parseChunk(input: ByteString, offset: Int, isLastMessage: Boolean, totalBytesRead: Long): StateResult = {
    @tailrec def parseTrailer(extension: String, lineStart: Int, headers: List[HttpHeader] = Nil,
                              headerCount: Int = 0): StateResult = {
      var errorInfo: ErrorInfo = null
      val lineEnd =
        try headerParser.parseHeaderLine(input, lineStart)()
        catch { case e: ParsingException ⇒ errorInfo = e.info; 0 }
      if (errorInfo eq null) {
        headerParser.resultHeader match {
          case EmptyHeader ⇒
            val lastChunk =
              if (extension.isEmpty && headers.isEmpty) HttpEntity.LastChunk else HttpEntity.LastChunk(extension, headers)
            emit(EntityChunk(lastChunk))
            emit(MessageEnd)
            setCompletionHandling(CompletionOk)
            if (isLastMessage) terminate()
            else startNewMessage(input, lineEnd)
          case header if headerCount < maxHeaderCount ⇒
            parseTrailer(extension, lineEnd, header :: headers, headerCount + 1)
          case _ ⇒ failEntityStream(s"Chunk trailer contains more than the configured limit of $maxHeaderCount headers")
        }
      } else failEntityStream(errorInfo)
    }

    def parseChunkBody(chunkSize: Int, extension: String, cursor: Int): StateResult =
      if (chunkSize > 0) {
        val chunkBodyEnd = cursor + chunkSize
        def result(terminatorLen: Int) = {
          emit(EntityChunk(HttpEntity.Chunk(input.slice(cursor, chunkBodyEnd).compact, extension)))
          Trampoline(_ ⇒ parseChunk(input, chunkBodyEnd + terminatorLen, isLastMessage, totalBytesRead + chunkSize))
        }
        byteChar(input, chunkBodyEnd) match {
          case '\r' if byteChar(input, chunkBodyEnd + 1) == '\n' ⇒ result(2)
          case '\n' ⇒ result(1)
          case x ⇒ failEntityStream("Illegal chunk termination")
        }
      } else parseTrailer(extension, cursor)

    @tailrec def parseChunkExtensions(chunkSize: Int, cursor: Int)(startIx: Int = cursor): StateResult =
      if (cursor - startIx <= maxChunkExtLength) {
        def extension = asciiString(input, startIx, cursor)
        byteChar(input, cursor) match {
          case '\r' if byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 2)
          case '\n' ⇒ parseChunkBody(chunkSize, extension, cursor + 1)
          case _ ⇒ parseChunkExtensions(chunkSize, cursor + 1)(startIx)
        }
      } else failEntityStream(s"HTTP chunk extension length exceeds configured limit of $maxChunkExtLength characters")

    @tailrec def parseSize(cursor: Int, size: Long): StateResult =
      if (size <= maxChunkSize) {
        byteChar(input, cursor) match {
          case c if CharacterClasses.HEXDIG(c) ⇒ parseSize(cursor + 1, size * 16 + CharUtils.hexValue(c))
          case ';' if cursor > offset ⇒ parseChunkExtensions(size.toInt, cursor + 1)()
          case '\r' if cursor > offset && byteChar(input, cursor + 1) == '\n' ⇒ parseChunkBody(size.toInt, "", cursor + 2)
          case c ⇒ failEntityStream(s"Illegal character '${escape(c)}' in chunk start")
        }
      } else failEntityStream(s"HTTP chunk size exceeds the configured limit of $maxChunkSize bytes")

    try parseSize(offset, 0)
    catch {
      case NotEnoughDataException ⇒ continue(input, offset)(parseChunk(_, _, isLastMessage, totalBytesRead))
    }
  }

  def emit(output: Output): Unit = result += output

  def continue(input: ByteString, offset: Int)(next: (ByteString, Int) ⇒ StateResult): StateResult = {
    state =
      math.signum(offset - input.length) match {
        case -1 ⇒
          val remaining = input.drop(offset)
          (more ⇒ next(remaining ++ more, 0))
        case 0 ⇒ next(_, 0)
        case 1 ⇒ throw new IllegalStateException
      }
    done()
  }

  def continue(next: (ByteString, Int) ⇒ StateResult): StateResult = {
    state = next(_, 0)
    done()
  }

  def failMessageStart(summary: String): StateResult = failMessageStart(summary, "")
  def failMessageStart(summary: String, detail: String): StateResult = failMessageStart(StatusCodes.BadRequest, summary, detail)
  def failMessageStart(status: StatusCode): StateResult = failMessageStart(status, status.defaultMessage)
  def failMessageStart(status: StatusCode, summary: String, detail: String = ""): StateResult = failMessageStart(status, ErrorInfo(summary, detail))
  def failMessageStart(status: StatusCode, info: ErrorInfo): StateResult = {
    emit(MessageStartError(status, info))
    setCompletionHandling(CompletionOk)
    terminate()
  }

  def failEntityStream(summary: String): StateResult = failEntityStream(summary, "")
  def failEntityStream(summary: String, detail: String): StateResult = failEntityStream(ErrorInfo(summary, detail))
  def failEntityStream(info: ErrorInfo): StateResult = {
    emit(EntityStreamError(info))
    setCompletionHandling(CompletionOk)
    terminate()
  }

  def terminate(): StateResult = {
    terminated = true
    done()
  }

  /**
   * Use [[continue]] or [[terminate]] to suspend or terminate processing.
   * Do not call this directly.
   */
  private def done(): StateResult = null // StateResult is a phantom type

  def contentType(cth: Option[`Content-Type`]) = cth match {
    case Some(x) ⇒ x.contentType
    case None    ⇒ ContentTypes.`application/octet-stream`
  }

  def emptyEntity(cth: Option[`Content-Type`]) =
    StrictEntityCreator(if (cth.isDefined) HttpEntity.empty(cth.get.contentType) else HttpEntity.Empty)

  def strictEntity(cth: Option[`Content-Type`], input: ByteString, bodyStart: Int,
                   contentLength: Int) =
    StrictEntityCreator(HttpEntity.Strict(contentType(cth), input.slice(bodyStart, bodyStart + contentLength)))

  def defaultEntity[A <: ParserOutput](cth: Option[`Content-Type`], contentLength: Long) =
    StreamedEntityCreator[A, UniversalEntity] { entityParts ⇒
      val data = entityParts.collect {
        case EntityPart(bytes)       ⇒ bytes
        case EntityStreamError(info) ⇒ throw EntityStreamException(info)
      }
      HttpEntity.Default(contentType(cth), contentLength, HttpEntity.limitableByteSource(data))
    }

  def chunkedEntity[A <: ParserOutput](cth: Option[`Content-Type`]) =
    StreamedEntityCreator[A, RequestEntity] { entityChunks ⇒
      val chunks = entityChunks.collect {
        case EntityChunk(chunk)      ⇒ chunk
        case EntityStreamError(info) ⇒ throw EntityStreamException(info)
      }
      HttpEntity.Chunked(contentType(cth), HttpEntity.limitableChunkSource(chunks))
    }

  def addTransferEncodingWithChunkedPeeled(headers: List[HttpHeader], teh: `Transfer-Encoding`): List[HttpHeader] =
    teh.withChunkedPeeled match {
      case Some(x) ⇒ x :: headers
      case None    ⇒ headers
    }

  def setCompletionHandling(completionHandling: CompletionHandling): Unit =
    this.completionHandling = completionHandling
}

private[http] object HttpMessageParser {
  sealed trait StateResult // phantom type for ensuring soundness of our parsing method setup
  final case class Trampoline(f: ByteString ⇒ StateResult) extends StateResult

  type CompletionHandling = () ⇒ Option[ErrorOutput]
  val CompletionOk: CompletionHandling = () ⇒ None
  val CompletionIsMessageStartError: CompletionHandling =
    () ⇒ Some(ParserOutput.MessageStartError(StatusCodes.BadRequest, ErrorInfo("Illegal HTTP message start")))
  val CompletionIsEntityStreamError: CompletionHandling =
    () ⇒ Some(ParserOutput.EntityStreamError(ErrorInfo("Entity stream truncation")))
}
