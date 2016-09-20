package akka.http.scaladsl.model.http2

import akka.http.impl.engine.ws.InternalCustomHeader

// FIXME: decide if InternalCustomHeader is really the right type to extend from
final case class Http2StreamIdHeader(streamId: Int) extends InternalCustomHeader("x-http2-stream-id")