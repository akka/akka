package akka.http.javadsl.server

import akka.http.javadsl.model.HttpResponse

trait RouteResult {}

trait Complete extends RouteResult {
  def getResponse: HttpResponse
}

trait Rejected extends RouteResult {
  def getRejections: java.lang.Iterable[Rejection]
}
