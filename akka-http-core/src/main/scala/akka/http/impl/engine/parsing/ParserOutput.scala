/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.parsing

import akka.http.scaladsl.model._
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
  sealed trait ErrorOutput extends MessageOutput

  final case class RequestStart(
    method: HttpMethod,
    uri: Uri,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Source[RequestOutput, Unit] ⇒ RequestEntity,
    expect100ContinueResponsePending: Boolean,
    closeRequested: Boolean) extends MessageStart with RequestOutput

  final case class ResponseStart(
    statusCode: StatusCode,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Source[ResponseOutput, Unit] ⇒ ResponseEntity,
    closeRequested: Boolean) extends MessageStart with ResponseOutput

  case object MessageEnd extends MessageOutput

  final case class EntityPart(data: ByteString) extends MessageOutput

  final case class EntityChunk(chunk: HttpEntity.ChunkStreamPart) extends MessageOutput

  final case class MessageStartError(status: StatusCode, info: ErrorInfo) extends MessageStart with ErrorOutput

  final case class EntityStreamError(info: ErrorInfo) extends ErrorOutput

  //////////// meta messages ///////////

  case object StreamEnd extends MessageOutput

  case object NeedMoreData extends MessageOutput

  case object NeedNextRequestMethod extends ResponseOutput

  final case class RemainingBytes(bytes: ByteString) extends ResponseOutput
}
