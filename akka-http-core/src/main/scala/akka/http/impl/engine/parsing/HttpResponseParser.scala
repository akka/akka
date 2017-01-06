/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.util.control.NoStackTrace
import akka.http.scaladsl.settings.ParserSettings
import akka.http.impl.model.parser.CharacterClasses
import akka.util.ByteString
import akka.http.scaladsl.model._
import headers._
import ParserOutput._

/**
 * INTERNAL API
 */
private[http] class HttpResponseParser(protected val settings: ParserSettings, protected val headerParser: HttpHeaderParser)
  extends HttpMessageParser[ResponseOutput] { self ⇒
  import HttpResponseParser._
  import HttpMessageParser._
  import settings._

  private[this] var contextForCurrentResponse: Option[ResponseContext] = None
  private[this] var statusCode: StatusCode = StatusCodes.OK

  final def createShallowCopy(): HttpResponseParser = new HttpResponseParser(settings, headerParser.createShallowCopy())

  final def setContextForNextResponse(responseContext: ResponseContext): Unit =
    if (contextForCurrentResponse.isEmpty) contextForCurrentResponse = Some(responseContext)

  final def onPull(): ResponseOutput =
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

  override final def emit(output: ResponseOutput): Unit = {
    if (output == MessageEnd) contextForCurrentResponse = None
    super.emit(output)
  }

  override protected def parseMessage(input: ByteString, offset: Int): StateResult =
    if (contextForCurrentResponse.isDefined) {
      var cursor = parseProtocol(input, offset)
      if (byteChar(input, cursor) == ' ') {
        cursor = parseStatus(input, cursor + 1)
        parseHeaderLines(input, cursor)
      } else onBadProtocol()
    } else {
      emit(NeedNextRequestMethod)
      continue(input, offset)(startNewMessage)
    }

  override final def onBadProtocol() = throw new ParsingException("The server-side HTTP version is not supported")

  private def parseStatus(input: ByteString, cursor: Int): Int = {
    def badStatusCode() = throw new ParsingException("Illegal response status code")
    def badStatusCodeSpecific(code: Int) = throw new ParsingException("Illegal response status code: " + code)
    def parseStatusCode() = {
      def intValue(offset: Int): Int = {
        val c = byteChar(input, cursor + offset)
        if (CharacterClasses.DIGIT(c)) c - '0' else badStatusCode()
      }
      val code = intValue(0) * 100 + intValue(1) * 10 + intValue(2)
      statusCode = code match {
        case 200 ⇒ StatusCodes.OK
        case code ⇒ StatusCodes.getForKey(code) match {
          case Some(x) ⇒ x
          case None    ⇒ customStatusCodes(code) getOrElse badStatusCodeSpecific(code)
        }
      }
    }
    if (byteChar(input, cursor + 3) == ' ') {
      parseStatusCode()
      val startIdx = cursor + 4
      @tailrec def skipReason(idx: Int): Int =
        if (idx - startIdx <= maxResponseReasonLength)
          if (byteChar(input, idx) == '\r' && byteChar(input, idx + 1) == '\n') idx + 2
          else skipReason(idx + 1)
        else throw new ParsingException("Response reason phrase exceeds the configured limit of " +
          maxResponseReasonLength + " characters")
      skipReason(startIdx)
    } else if (byteChar(input, cursor + 3) == '\r' && byteChar(input, cursor + 4) == '\n') {
      throw new ParsingException("Status code misses trailing space")
    } else badStatusCode
  }

  def handleInformationalResponses: Boolean = true

  // http://tools.ietf.org/html/rfc7230#section-3.3
  protected final def parseEntity(headers: List[HttpHeader], protocol: HttpProtocol, input: ByteString, bodyStart: Int,
                                  clh: Option[`Content-Length`], cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`],
                                  expect100continue: Boolean, hostHeaderPresent: Boolean, closeAfterResponseCompletion: Boolean): StateResult = {

    def emitResponseStart(
      createEntity: EntityCreator[ResponseOutput, ResponseEntity],
      headers:      List[HttpHeader]                              = headers) = {
      val close =
        contextForCurrentResponse.get.oneHundredContinueTrigger match {
          case None ⇒ closeAfterResponseCompletion
          case Some(trigger) if statusCode.isSuccess ⇒
            trigger.trySuccess(())
            closeAfterResponseCompletion
          case Some(trigger) ⇒
            trigger.tryFailure(OneHundredContinueError)
            true
        }
      emit(ResponseStart(statusCode, protocol, headers, createEntity, close))
    }

    def finishEmptyResponse() =
      statusCode match {
        case _: StatusCodes.Informational if handleInformationalResponses ⇒
          if (statusCode == StatusCodes.Continue)
            contextForCurrentResponse.get.oneHundredContinueTrigger.foreach(_.trySuccess(()))

          // http://tools.ietf.org/html/rfc7231#section-6.2 says:
          // "A client MUST be able to parse one or more 1xx responses received prior to a final response,
          // even if the client does not expect one."
          // so we simply drop this interim response and start parsing the next one
          startNewMessage(input, bodyStart)
        case _ ⇒
          emitResponseStart(emptyEntity(cth))
          setCompletionHandling(HttpMessageParser.CompletionOk)
          emit(MessageEnd)
          startNewMessage(input, bodyStart)
      }

    if (statusCode.allowsEntity && (contextForCurrentResponse.get.requestMethod != HttpMethods.HEAD)) {
      teh match {
        case None ⇒ clh match {
          case Some(`Content-Length`(contentLength)) ⇒
            if (contentLength == 0) finishEmptyResponse()
            else if (contentLength <= input.size - bodyStart) {
              val cl = contentLength.toInt
              emitResponseStart(strictEntity(cth, input, bodyStart, cl))
              setCompletionHandling(HttpMessageParser.CompletionOk)
              emit(MessageEnd)
              startNewMessage(input, bodyStart + cl)
            } else {
              emitResponseStart(defaultEntity(cth, contentLength))
              parseFixedLengthBody(contentLength, closeAfterResponseCompletion)(input, bodyStart)
            }
          case None ⇒
            emitResponseStart {
              StreamedEntityCreator { entityParts ⇒
                val data = entityParts.collect { case EntityPart(bytes) ⇒ bytes }
                HttpEntity.CloseDelimited(contentType(cth), HttpEntity.limitableByteSource(data))
              }
            }
            setCompletionHandling(HttpMessageParser.CompletionOk)
            parseToCloseBody(input, bodyStart, totalBytesRead = 0)
        }

        case Some(te) ⇒
          val completedHeaders = addTransferEncodingWithChunkedPeeled(headers, te)
          if (te.isChunked) {
            if (clh.isEmpty) {
              emitResponseStart(chunkedEntity(cth), completedHeaders)
              parseChunk(input, bodyStart, closeAfterResponseCompletion, totalBytesRead = 0L)
            } else failMessageStart("A chunked response must not contain a Content-Length header.")
          } else parseEntity(completedHeaders, protocol, input, bodyStart, clh, cth, teh = None,
            expect100continue, hostHeaderPresent, closeAfterResponseCompletion)
      }
    } else finishEmptyResponse()
  }

  private def parseToCloseBody(input: ByteString, bodyStart: Int, totalBytesRead: Long): StateResult = {
    val newTotalBytes = totalBytesRead + math.max(0, input.length - bodyStart)
    if (input.length > bodyStart)
      emit(EntityPart(input.drop(bodyStart).compact))
    continue(parseToCloseBody(_, _, newTotalBytes))
  }
}

private[http] object HttpResponseParser {
  /**
   * @param requestMethod the request's HTTP method
   * @param oneHundredContinueTrigger if the request contains an `Expect: 100-continue` header this option contains
   *                                  a promise whose completion either triggers the sending of the (suspended)
   *                                  request entity or the closing of the connection (for error completion)
   */
  private[http] final case class ResponseContext(
    requestMethod:             HttpMethod,
    oneHundredContinueTrigger: Option[Promise[Unit]])

  private[http] object OneHundredContinueError
    extends RuntimeException("Received error response for request with `Expect: 100-continue` header")
    with NoStackTrace
}
