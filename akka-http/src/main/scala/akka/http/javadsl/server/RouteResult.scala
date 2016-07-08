package akka.http.javadsl.server

import akka.http.javadsl.model.HttpResponse

trait RouteResult {}

trait Complete extends RouteResult {
  def getResponse: HttpResponse
}

trait Rejected extends RouteResult {
  def getRejections: java.lang.Iterable[Rejection]
}

object RouteResults {
  import akka.http.scaladsl.{ server ⇒ s }
  import akka.japi.Util
  import scala.language.implicitConversions
  import akka.http.impl.util.JavaMapping
  import JavaMapping.Implicits._
  import RoutingJavaMapping._

  def complete(response: HttpResponse): Complete = {
    s.RouteResult.Complete(JavaMapping.toScala(response))
  }

  def rejected(rejections: java.lang.Iterable[Rejection]): Rejected = {
    s.RouteResult.Rejected(Util.immutableSeq(rejections).map(_.asScala))
  }

}
