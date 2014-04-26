/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import org.reactivestreams.api.Producer
import akka.http.model._
import akka.util.ByteString

private[http] sealed trait ParserOutput

private[http] object ParserOutput {
  sealed trait RequestOutput extends ParserOutput
  sealed trait ResponseOutput extends ParserOutput
  sealed trait MessageStart extends ParserOutput
  sealed trait MessageOutput extends RequestOutput with ResponseOutput

  case class RequestStart(
    method: HttpMethod,
    uri: Uri,
    protocol: HttpProtocol,
    headers: List[HttpHeader],
    createEntity: Producer[RequestOutput] ⇒ HttpEntity.Regular,
    closeAfterResponseCompletion: Boolean) extends MessageStart with RequestOutput

  case class ResponseStart() extends MessageStart with ResponseOutput

  case class EntityPart(data: ByteString) extends MessageOutput

  case class EntityChunk(chunk: HttpEntity.ChunkStreamPart) extends MessageOutput

  case class ParseError(status: StatusCode, info: ErrorInfo) extends MessageStart with MessageOutput
}
