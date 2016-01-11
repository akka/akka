/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import org.reactivestreams.Publisher
import scala.annotation.tailrec
import akka.http.model.parser.CharacterClasses
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.http.model._
import headers._
import HttpResponseParser.NoMethod

/**
 * INTERNAL API
 */
private[http] class HttpResponseParser(_settings: ParserSettings,
                                       dequeueRequestMethodForNextResponse: () ⇒ HttpMethod = () ⇒ NoMethod)(_headerParser: HttpHeaderParser = HttpHeaderParser(_settings))(implicit fm: FlowMaterializer)
  extends HttpMessageParser[ParserOutput.ResponseOutput](_settings, _headerParser) {
  import settings._

  private[this] var requestMethodForCurrentResponse: HttpMethod = NoMethod
  private[this] var statusCode: StatusCode = StatusCodes.OK

  def copyWith(warnOnIllegalHeader: ErrorInfo ⇒ Unit, dequeueRequestMethodForNextResponse: () ⇒ HttpMethod): HttpResponseParser =
    new HttpResponseParser(settings, dequeueRequestMethodForNextResponse)(headerParser.copyWith(warnOnIllegalHeader))

  override def startNewMessage(input: ByteString, offset: Int): StateResult = {
    requestMethodForCurrentResponse = dequeueRequestMethodForNextResponse()
    super.startNewMessage(input, offset)
  }

  def parseMessage(input: ByteString, offset: Int): StateResult =
    if (requestMethodForCurrentResponse ne NoMethod) {
      var cursor = parseProtocol(input, offset)
      if (byteChar(input, cursor) == ' ') {
        cursor = parseStatusCode(input, cursor + 1)
        cursor = parseReason(input, cursor)()
        parseHeaderLines(input, cursor)
      } else badProtocol
    } else fail("Unexpected server response", input.drop(offset).utf8String)

  def badProtocol = throw new ParsingException("The server-side HTTP version is not supported")

  def parseStatusCode(input: ByteString, cursor: Int): Int = {
    def badStatusCode = throw new ParsingException("Illegal response status code")
    def intValue(offset: Int): Int = {
      val c = byteChar(input, cursor + offset)
      if (CharacterClasses.DIGIT(c)) c - '0' else badStatusCode
    }
    if (byteChar(input, cursor + 3) == ' ') {
      val code = intValue(0) * 100 + intValue(1) * 10 + intValue(2)
      statusCode = code match {
        case 200 ⇒ StatusCodes.OK
        case _ ⇒ StatusCodes.getForKey(code) match {
          case Some(x) ⇒ x
          case None    ⇒ customStatusCodes(code) getOrElse badStatusCode
        }
      }
      cursor + 4
    } else badStatusCode
  }

  @tailrec private def parseReason(input: ByteString, startIx: Int)(cursor: Int = startIx): Int =
    if (cursor - startIx <= maxResponseReasonLength)
      if (byteChar(input, cursor) == '\r' && byteChar(input, cursor + 1) == '\n') cursor + 2
      else parseReason(input, startIx)(cursor + 1)
    else throw new ParsingException("Response reason phrase exceeds the configured limit of " +
      maxResponseReasonLength + " characters")

  // http://tools.ietf.org/html/rfc7230#section-3.3
  def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                  hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult = {
    def emitResponseStart(createEntity: Publisher[ParserOutput.ResponseOutput] ⇒ ResponseEntity,
                          headers: List[HttpHeader] = headers) =
      emit(ParserOutput.ResponseStart(statusCode, protocol, headers, createEntity, closeAfterResponseCompletion))
    def finishEmptyResponse() = {
      emitResponseStart(emptyEntity(cth))
      startNewMessage(input, bodyStart)
    }

    if (statusCode.allowsEntity && (requestMethodForCurrentResponse ne HttpMethods.HEAD)) {
      teh match {
        case None ⇒ clh match {
          case Some(`Content-Length`(contentLength)) ⇒
            if (contentLength > maxContentLength)
              fail(s"Response Content-Length $contentLength exceeds the configured limit of $maxContentLength")
            else if (contentLength == 0) finishEmptyResponse()
            else if (contentLength < input.size - bodyStart) {
              val cl = contentLength.toInt
              emitResponseStart(strictEntity(cth, input, bodyStart, cl))
              startNewMessage(input, bodyStart + cl)
            } else {
              emitResponseStart(defaultEntity(cth, contentLength))
              parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
            }
          case None ⇒
            emitResponseStart { entityParts ⇒
              val data = Flow(entityParts).collect { case ParserOutput.EntityPart(bytes) ⇒ bytes }.toPublisher()
              HttpEntity.CloseDelimited(contentType(cth), data)
            }
            parseToCloseBody(input, bodyStart)
        }

        case Some(te) ⇒
          val completedHeaders = addTransferEncodingWithChunkedPeeled(headers, te)
          if (te.isChunked) {
            if (clh.isEmpty) {
              emitResponseStart(chunkedEntity(cth), completedHeaders)
              parseChunk(input, bodyStart, closeAfterResponseCompletion)
            } else fail("A chunked request must not contain a Content-Length header.")
          } else parseEntity(completedHeaders, protocol, input, bodyStart, clh, cth, teh = None, hostHeaderPresent,
            closeAfterResponseCompletion)
      }
    } else finishEmptyResponse()
  }

  // currently we do not check for `settings.maxContentLength` overflow
  def parseToCloseBody(input: ByteString, bodyStart: Int): StateResult = {
    if (input.length > bodyStart)
      emit(ParserOutput.EntityPart(input drop bodyStart))
    continue(parseToCloseBody)
  }
}

/**
 * INTERNAL API
 */
private[http] object HttpResponseParser {
  val NoMethod = HttpMethod.custom("NONE", safe = false, idempotent = false, entityAccepted = false)
}