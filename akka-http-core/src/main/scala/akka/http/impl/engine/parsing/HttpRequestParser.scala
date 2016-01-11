/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.parsing

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import akka.http.ParserSettings

import scala.annotation.tailrec
import akka.actor.ActorRef
import akka.stream.stage.{ Context, PushPullStage }
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.http.impl.engine.ws.Handshake
import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util.identityFunc
import akka.http.impl.engine.TokenSourceActor
import akka.http.scaladsl.model._
import headers._
import StatusCodes._
import ParserOutput._

/**
 * INTERNAL API
 */
private[http] class HttpRequestParser(_settings: ParserSettings,
                                      rawRequestUriHeader: Boolean,
                                      _headerParser: HttpHeaderParser,
                                      oneHundredContinueRef: () ⇒ Option[ActorRef] = () ⇒ None)
  extends HttpMessageParser[RequestOutput](_settings, _headerParser) {
  import HttpMessageParser._
  import settings._

  private[this] var method: HttpMethod = _
  private[this] var uri: Uri = _
  private[this] var uriBytes: Array[Byte] = _

  def createShallowCopy(oneHundredContinueRef: () ⇒ Option[ActorRef]): HttpRequestParser =
    new HttpRequestParser(settings, rawRequestUriHeader, headerParser.createShallowCopy(), oneHundredContinueRef)

  def parseMessage(input: ByteString, offset: Int): StateResult = {
    var cursor = parseMethod(input, offset)
    cursor = parseRequestTarget(input, cursor)
    cursor = parseProtocol(input, cursor)
    if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n')
      parseHeaderLines(input, cursor + 2)
    else badProtocol
  }

  def parseMethod(input: ByteString, cursor: Int): Int = {
    @tailrec def parseCustomMethod(ix: Int = 0, sb: JStringBuilder = new JStringBuilder(16)): Int =
      if (ix < maxMethodLength) {
        byteChar(input, cursor + ix) match {
          case ' ' ⇒
            customMethods(sb.toString) match {
              case Some(m) ⇒
                method = m
                cursor + ix + 1
              case None ⇒ throw new ParsingException(NotImplemented, ErrorInfo("Unsupported HTTP method", sb.toString))
            }
          case c ⇒ parseCustomMethod(ix + 1, sb.append(c))
        }
      } else throw new ParsingException(BadRequest,
        ErrorInfo("Unsupported HTTP method", s"HTTP method too long (started with '${sb.toString}'). " +
          "Increase `akka.http.server.parsing.max-method-length` to support HTTP methods with more characters."))

    @tailrec def parseMethod(meth: HttpMethod, ix: Int = 1): Int =
      if (ix == meth.value.length)
        if (byteChar(input, cursor + ix) == ' ') {
          method = meth
          cursor + ix + 1
        } else parseCustomMethod()
      else if (byteChar(input, cursor + ix) == meth.value.charAt(ix)) parseMethod(meth, ix + 1)
      else parseCustomMethod()

    import HttpMethods._
    byteChar(input, cursor) match {
      case 'G' ⇒ parseMethod(GET)
      case 'P' ⇒ byteChar(input, cursor + 1) match {
        case 'O' ⇒ parseMethod(POST, 2)
        case 'U' ⇒ parseMethod(PUT, 2)
        case 'A' ⇒ parseMethod(PATCH, 2)
        case _   ⇒ parseCustomMethod()
      }
      case 'D' ⇒ parseMethod(DELETE)
      case 'H' ⇒ parseMethod(HEAD)
      case 'O' ⇒ parseMethod(OPTIONS)
      case 'T' ⇒ parseMethod(TRACE)
      case 'C' ⇒ parseMethod(CONNECT)
      case _   ⇒ parseCustomMethod()
    }
  }

  def parseRequestTarget(input: ByteString, cursor: Int): Int = {
    val uriStart = cursor
    val uriEndLimit = cursor + maxUriLength

    @tailrec def findUriEnd(ix: Int = cursor): Int =
      if (ix == input.length) throw NotEnoughDataException
      else if (CharacterClasses.WSPCRLF(input(ix).toChar)) ix
      else if (ix < uriEndLimit) findUriEnd(ix + 1)
      else throw new ParsingException(RequestUriTooLong,
        s"URI length exceeds the configured limit of $maxUriLength characters")

    val uriEnd = findUriEnd()
    try {
      uriBytes = input.iterator.slice(uriStart, uriEnd).toArray[Byte] // TODO: can we reduce allocations here?
      uri = Uri.parseHttpRequestTarget(uriBytes, mode = uriParsingMode)
    } catch {
      case IllegalUriException(info) ⇒ throw new ParsingException(BadRequest, info)
    }
    uriEnd + 1
  }

  def badProtocol = throw new ParsingException(HTTPVersionNotSupported)

  // http://tools.ietf.org/html/rfc7230#section-3.3
  def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                  expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult =
    if (hostHeaderPresent || protocol == HttpProtocols.`HTTP/1.0`) {
      def emitRequestStart(createEntity: Source[RequestOutput, Unit] ⇒ RequestEntity,
                           headers: List[HttpHeader] = headers) = {
        val allHeaders0 =
          if (rawRequestUriHeader) `Raw-Request-URI`(new String(uriBytes, HttpCharsets.`US-ASCII`.nioCharset)) :: headers
          else headers

        val allHeaders =
          if (method == HttpMethods.GET) {
            Handshake.Server.isWebsocketUpgrade(headers, hostHeaderPresent) match {
              case Some(upgrade) ⇒ upgrade :: allHeaders0
              case None          ⇒ allHeaders0
            }
          } else allHeaders0

        emit(RequestStart(method, uri, protocol, allHeaders, createEntity, expect100continue, closeAfterResponseCompletion))
      }

      def expect100continueHandling[T, Mat]: Source[T, Mat] ⇒ Source[T, Mat] =
        if (expect100continue) {
          _.via(Flow[T].transform(() ⇒ new PushPullStage[T, T] {
            private var oneHundredContinueSent = false
            def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)
            def onPull(ctx: Context[T]) = {
              if (!oneHundredContinueSent) {
                val ref = oneHundredContinueRef().getOrElse(throw new IllegalStateException("oneHundredContinueRef unavailable"))
                ref ! TokenSourceActor.Trigger
                oneHundredContinueSent = true
              }
              ctx.pull()
            }
          }).named("expect100continueTrigger"))
        } else identityFunc

      teh match {
        case None ⇒
          val contentLength = clh match {
            case Some(`Content-Length`(len)) ⇒ len
            case None                        ⇒ 0
          }
          if (contentLength > maxContentLength)
            failMessageStart(RequestEntityTooLarge,
              summary = s"Request Content-Length of $contentLength bytes exceeds the configured limit of $maxContentLength bytes",
              detail = "Consider increasing the value of akka.http.server.parsing.max-content-length")
          else if (contentLength == 0) {
            emitRequestStart(emptyEntity(cth))
            setCompletionHandling(HttpMessageParser.CompletionOk)
            startNewMessage(input, bodyStart)
          } else if (!method.isEntityAccepted) {
            failMessageStart(UnprocessableEntity, s"${method.name} requests must not have an entity")
          } else if (contentLength <= input.size - bodyStart) {
            val cl = contentLength.toInt
            emitRequestStart(strictEntity(cth, input, bodyStart, cl))
            setCompletionHandling(HttpMessageParser.CompletionOk)
            startNewMessage(input, bodyStart + cl)
          } else {
            emitRequestStart(defaultEntity(cth, contentLength, expect100continueHandling))
            parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
          }

        case Some(_) if !method.isEntityAccepted ⇒
          failMessageStart(UnprocessableEntity, s"${method.name} requests must not have an entity")

        case Some(te) ⇒
          val completedHeaders = addTransferEncodingWithChunkedPeeled(headers, te)
          if (te.isChunked) {
            if (clh.isEmpty) {
              emitRequestStart(chunkedEntity(cth, expect100continueHandling), completedHeaders)
              parseChunk(input, bodyStart, closeAfterResponseCompletion, totalBytesRead = 0L)
            } else failMessageStart("A chunked request must not contain a Content-Length header.")
          } else parseEntity(completedHeaders, protocol, input, bodyStart, clh, cth, teh = None,
            expect100continue, hostHeaderPresent, closeAfterResponseCompletion)
      }
    } else failMessageStart("Request is missing required `Host` header")

  def failWithChunkedEntityTooLong(totalBytesRead: Long): StateResult =
    failEntityStream(
      summary = s"Aggregated data length of chunked request entity of $totalBytesRead " +
        s"bytes exceeds the configured limit of $maxContentLength bytes",
      detail = "Consider increasing the value of akka.http.server.parsing.max-content-length")
}
