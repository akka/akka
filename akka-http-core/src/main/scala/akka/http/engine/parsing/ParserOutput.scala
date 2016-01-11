/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import akka.http.model._
import akka.util.ByteString
import akka.stream.scaladsl.Source

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
    createEntity: Source[RequestOutput] ⇒ RequestEntity,
    closeAfterResponseCompletion: Boolean) extends MessageStart with RequestOutput

  final case class ResponseStart(
    statusCode: StatusCode,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Source[ResponseOutput] ⇒ ResponseEntity,
    closeAfterResponseCompletion: Boolean) extends MessageStart with ResponseOutput

  case object MessageEnd extends MessageOutput

  final case class EntityPart(data: ByteString) extends MessageOutput

  final case class EntityChunk(chunk: HttpEntity.ChunkStreamPart) extends MessageOutput

  final case class ParseError(status: StatusCode, info: ErrorInfo) extends MessageStart with MessageOutput
}
