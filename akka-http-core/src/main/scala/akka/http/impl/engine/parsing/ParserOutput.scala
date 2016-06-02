/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.impl.fusing.SubSource

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
    method:            HttpMethod,
    uri:               Uri,
    protocol:          HttpProtocol,
    headers:           List[HttpHeader],
    createEntity:      EntityCreator[RequestOutput, RequestEntity],
    expect100Continue: Boolean,
    closeRequested:    Boolean) extends MessageStart with RequestOutput

  final case class ResponseStart(
    statusCode:     StatusCode,
    protocol:       HttpProtocol,
    headers:        List[HttpHeader],
    createEntity:   EntityCreator[ResponseOutput, ResponseEntity],
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

  //////////////////////////////////////

  sealed abstract class EntityCreator[-A <: ParserOutput, +B >: HttpEntity.Strict <: HttpEntity] extends (Source[A, NotUsed] ⇒ B)

  final case class StrictEntityCreator(entity: HttpEntity.Strict) extends EntityCreator[ParserOutput, HttpEntity.Strict] {
    def apply(parts: Source[ParserOutput, NotUsed]) = {
      // We might need to drain stray empty tail streams which will be read by no one.
      SubSource.kill(parts)
      entity
    }
  }
  final case class StreamedEntityCreator[-A <: ParserOutput, +B >: HttpEntity.Strict <: HttpEntity](creator: Source[A, NotUsed] ⇒ B)
    extends EntityCreator[A, B] {
    def apply(parts: Source[A, NotUsed]) = creator(parts)
  }
}
