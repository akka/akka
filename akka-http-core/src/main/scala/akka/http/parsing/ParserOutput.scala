/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import org.reactivestreams.api.Producer
import akka.http.model._
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[http] sealed trait ParserOutput

/**
 * INTERNAL API
 */
private[http] object ParserOutput {
  sealed trait RequestOutput extends ParserOutput
  sealed trait ResponseOutput extends ParserOutput
  sealed trait MessageStart extends ParserOutput
  sealed trait MessageOutput extends RequestOutput with ResponseOutput

  final case class RequestStart(
    method: HttpMethod,
    uri: Uri,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Producer[RequestOutput] ⇒ HttpEntity.Regular,
    closeAfterResponseCompletion: Boolean) extends MessageStart with RequestOutput

  final case class ResponseStart(
    statusCode: StatusCode,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Producer[ResponseOutput] ⇒ HttpEntity,
    closeAfterResponseCompletion: Boolean) extends MessageStart with ResponseOutput

  final case class EntityPart(data: ByteString) extends MessageOutput

  final case class EntityChunk(chunk: HttpEntity.ChunkStreamPart) extends MessageOutput

  final case class ParseError(status: StatusCode, info: ErrorInfo) extends MessageStart with MessageOutput
}
