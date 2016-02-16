package akka.http.scaladsl.model.headers

import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }

/**
 * Include this header to request a TLS renegotiation with client authentication.
 * After the renegotiation handshake is complete the given request will be updated to
 * include the new TLS session data and will then be injected into the pipeline for the
 * user to handle. The entity of the passed request must be an instance of [[HttpEntity.Strict]].
 */
case class RequestClientCertificate(request: HttpRequest) extends CustomHeader {
  require(request.entity.isInstanceOf[HttpEntity.Strict], s"The entity of the request passed to RequestClientCertificate must be strict but was ${request.entity}")

  def renderInRequests(): Boolean = false
  def renderInResponses(): Boolean = false
  def name(): String = "RequestClientCertificate "
  def value(): String = ""
}