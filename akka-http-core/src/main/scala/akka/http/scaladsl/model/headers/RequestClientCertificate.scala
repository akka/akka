package akka.http.scaladsl.model.headers

import akka.http.scaladsl.model.HttpResponse

case class RequestClientCertificate(responseGenerator: `Tls-Session-Info` ⇒ HttpResponse) extends CustomHeader {
  def renderInRequests(): Boolean = false
  def renderInResponses(): Boolean = false
  def name(): String = "RequestClientCertificate "
  def value(): String = ""
}