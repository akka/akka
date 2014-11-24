/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import scala.annotation.tailrec
import akka.http.model.parser.CharacterClasses
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.http.model._
import headers._
import HttpResponseParser.NoMethod
import ParserOutput._

/**
 * INTERNAL API
 */
private[http] class HttpResponseParser(_settings: ParserSettings,
                                       _headerParser: HttpHeaderParser,
                                       dequeueRequestMethodForNextResponse: () ⇒ HttpMethod = () ⇒ NoMethod)
  extends HttpMessageParser[ResponseOutput](_settings, _headerParser) {
  import settings._

  private[this] var requestMethodForCurrentResponse: HttpMethod = NoMethod
  private[this] var statusCode: StatusCode = StatusCodes.OK

  def createShallowCopy(dequeueRequestMethodForNextResponse: () ⇒ HttpMethod): HttpResponseParser =
    new HttpResponseParser(settings, headerParser.createShallowCopy(), dequeueRequestMethodForNextResponse)

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
    } else failMessageStart("Unexpected server response", input.drop(offset).utf8String)

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
                  expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult = {
    def emitResponseStart(createEntity: Source[ResponseOutput] ⇒ ResponseEntity,
                          headers: List[HttpHeader] = headers) =
      emit(ResponseStart(statusCode, protocol, headers, createEntity, closeAfterResponseCompletion))
    def finishEmptyResponse() = {
      emitResponseStart(emptyEntity(cth))
      setCompletionHandling(HttpMessageParser.CompletionOk)
      startNewMessage(input, bodyStart)
    }

    if (statusCode.allowsEntity && (requestMethodForCurrentResponse ne HttpMethods.HEAD)) {
      teh match {
        case None ⇒ clh match {
          case Some(`Content-Length`(contentLength)) ⇒
            if (contentLength > maxContentLength)
              failMessageStart(s"Response Content-Length $contentLength exceeds the configured limit of $maxContentLength")
            else if (contentLength == 0) finishEmptyResponse()
            else if (contentLength < input.size - bodyStart) {
              val cl = contentLength.toInt
              emitResponseStart(strictEntity(cth, input, bodyStart, cl))
              setCompletionHandling(HttpMessageParser.CompletionOk)
              startNewMessage(input, bodyStart + cl)
            } else {
              emitResponseStart(defaultEntity(cth, contentLength))
              parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
            }
          case None ⇒
            emitResponseStart { entityParts ⇒
              val data = entityParts.collect { case EntityPart(bytes) ⇒ bytes }
              HttpEntity.CloseDelimited(contentType(cth), data)
            }
            setCompletionHandling(HttpMessageParser.CompletionOk)
            parseToCloseBody(input, bodyStart)
        }

        case Some(te) ⇒
          val completedHeaders = addTransferEncodingWithChunkedPeeled(headers, te)
          if (te.isChunked) {
            if (clh.isEmpty) {
              emitResponseStart(chunkedEntity(cth), completedHeaders)
              parseChunk(input, bodyStart, closeAfterResponseCompletion)
            } else failMessageStart("A chunked response must not contain a Content-Length header.")
          } else parseEntity(completedHeaders, protocol, input, bodyStart, clh, cth, teh = None,
            expect100continue, hostHeaderPresent, closeAfterResponseCompletion)
      }
    } else finishEmptyResponse()
  }

  // currently we do not check for `settings.maxContentLength` overflow
  def parseToCloseBody(input: ByteString, bodyStart: Int): StateResult = {
    if (input.length > bodyStart)
      emit(EntityPart(input drop bodyStart))
    continue(parseToCloseBody)
  }
}

/**
 * INTERNAL API
 */
private[http] object HttpResponseParser {
  val NoMethod = HttpMethod.custom("NONE", safe = false, idempotent = false, entityAccepted = false)
}