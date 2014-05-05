/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import java.lang.{ StringBuilder ⇒ JStringBuilder }
import org.reactivestreams.api.Producer
import scala.annotation.tailrec
import akka.actor.ActorRefFactory
import akka.stream2.Flow
import akka.http.model.parser.CharacterClasses
import akka.util.ByteString
import akka.http.model._
import headers._
import StatusCodes._

private[http] class HttpRequestParser(_settings: ParserSettings,
                                      rawRequestUriHeader: Boolean = false)(_headerParser: HttpHeaderParser = HttpHeaderParser(_settings))(implicit refFactory: ActorRefFactory)
  extends HttpMessageParser[ParserOutput.RequestOutput](_settings, _headerParser) {

  private[this] var method: HttpMethod = _
  private[this] var uri: Uri = _
  private[this] var uriBytes: Array[Byte] = _

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit): HttpRequestParser =
    new HttpRequestParser(settings, rawRequestUriHeader)(headerParser.copyWith(warnOnIllegalHeader))

  def parseMessage(input: ByteString, offset: Int): StateResult = {
    var cursor = parseMethod(input, offset)
    cursor = parseRequestTarget(input, cursor)
    cursor = parseProtocol(input, cursor)
    if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n')
      parseHeaderLines(input, cursor + 2)
    else {
      println("MARK: " + input.utf8String.flatMap(escape))
      badProtocol
    }
  }

  def parseMethod(input: ByteString, cursor: Int): Int = {
    @tailrec def parseCustomMethod(ix: Int = 0, sb: JStringBuilder = new JStringBuilder(16)): Int =
      if (ix < 16) { // hard-coded maximum custom method length
        byteChar(input, cursor + ix) match {
          case ' ' ⇒
            HttpMethods.getForKey(sb.toString) match {
              case Some(m) ⇒
                method = m
                cursor + ix + 1
              case None ⇒ parseCustomMethod(Int.MaxValue, sb)
            }
          case c ⇒ parseCustomMethod(ix + 1, sb.append(c))
        }
      } else throw new ParsingException(NotImplemented, ErrorInfo("Unsupported HTTP method", sb.toString))

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
    val uriEndLimit = cursor + settings.maxUriLength

    @tailrec def findUriEnd(ix: Int = cursor): Int =
      if (ix == input.length) throw NotEnoughDataException
      else if (CharacterClasses.WSPCRLF(input(ix).toChar)) ix
      else if (ix < uriEndLimit) findUriEnd(ix + 1)
      else throw new ParsingException(RequestUriTooLong,
        s"URI length exceeds the configured limit of ${settings.maxUriLength} characters")

    val uriEnd = findUriEnd()
    try {
      uriBytes = input.iterator.slice(uriStart, uriEnd).toArray[Byte] // TODO: can we reduce allocations here?
      uri = Uri.parseHttpRequestTarget(uriBytes, mode = settings.uriParsingMode)
    } catch {
      case e: IllegalUriException ⇒ throw new ParsingException(BadRequest, e.info)
    }
    uriEnd + 1
  }

  def badProtocol = throw new ParsingException(HTTPVersionNotSupported)

  // http://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3
  def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                  hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult =
    if (hostHeaderPresent || protocol == HttpProtocols.`HTTP/1.0`) {
      val contentType = cth match {
        case Some(x) ⇒ x.contentType
        case None    ⇒ ContentTypes.`application/octet-stream`
      }
      def emitRequestStart(createEntity: Producer[ParserOutput.RequestOutput] ⇒ HttpEntity.Regular) =
        emit(ParserOutput.RequestStart(method, uri, protocol, headers, createEntity, closeAfterResponseCompletion))

      teh match {
        case None ⇒
          val contentLength = clh match {
            case Some(`Content-Length`(len)) ⇒ len
            case None                        ⇒ 0
          }
          if (contentLength > settings.maxContentLength)
            fail(RequestEntityTooLarge,
              s"Request Content-Length $contentLength exceeds the configured limit of $settings.maxContentLength")
          else {
            emitRequestStart { entityParts ⇒
              val data = Flow(entityParts).collect { case ParserOutput.EntityPart(bytes) ⇒ bytes }.toProducer
              HttpEntity.Default(contentType, contentLength, data)
            }
            if (contentLength == 0) startNewMessage(input, bodyStart)
            else parseFixedLengthBody(contentLength)(input, bodyStart)
          }

        case Some(te) ⇒
          if (te.encodings.size == 1 && te.hasChunked) {
            if (clh.isEmpty) {
              emitRequestStart { entityChunks ⇒
                val chunks = Flow(entityChunks).collect { case ParserOutput.EntityChunk(chunk) ⇒ chunk }.toProducer
                HttpEntity.Chunked(contentType, chunks)
              }
              parseChunk(input, bodyStart)
            } else fail("A chunked request must not contain a Content-Length header.")
          } else fail(NotImplemented, s"`$te` is not supported by this server")
      }
    } else fail("Request is missing required `Host` header")
}