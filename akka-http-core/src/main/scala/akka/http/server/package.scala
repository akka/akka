package akka.http

import spray.http.{HttpResponsePart, HttpResponse, HttpRequestPart, HttpRequest}
import rx.async.api.{Consumer, Producer}
import akka.util.ByteString

package object server {
  // request with empty body for now
  type HttpRequestHeaders = HttpRequest
  type HttpRequestStream = (HttpRequestHeaders, Producer[ByteString])

  type HttpResponseHeaders = HttpResponse
  type HttpResponseStream = (HttpResponseHeaders, Producer[ByteString])

  type HttpStream = (Producer[HttpRequestStream], Consumer[HttpResponseStream])
  type HttpPartStream = (Producer[HttpRequestPart], Consumer[HttpResponsePart])
}
