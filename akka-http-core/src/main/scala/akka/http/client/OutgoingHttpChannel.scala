/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.http.model.{ HttpResponse, HttpRequest }
import org.reactivestreams.api.Processor
import java.net.InetSocketAddress

/**
 * A `HttpClientProcessor` models an HTTP client as a stream processor that provides
 * responses for requests with an attached context object of a custom type,
 * which is funneled through and completely transparent to the processor itself.
 */
trait HttpClientProcessor[T] extends Processor[(HttpRequest, T), (HttpResponse, T)]

/**
 * An `OutgoingHttpChannel` is a provision of an `HttpClientProcessor` with potentially
 * available additional information about the client or server.
 */
sealed trait OutgoingHttpChannel {
  def processor[T]: HttpClientProcessor[T]
}

/**
 * An `OutgoingHttpChannel` with a single outgoing HTTP connection as the underlying transport.
 */
case class OutgoingHttpConnection(remoteAddress: InetSocketAddress,
                                  localAddress: InetSocketAddress,
                                  untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
  def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
}

/**
 * An `OutgoingHttpChannel` with a connection pool to a specific host/port as the underlying transport.
 */
case class HttpHostChannel(host: String, port: Int,
                           untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
  def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
}

/**
 * A general `OutgoingHttpChannel` with connection pools to all possible host/port combinations
 * as the underlying transport.
 */
case class HttpRequestChannel(untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
  def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
}
