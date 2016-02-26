/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.server

import akka.actor.ActorSystem
import akka.http.scaladsl.{ Http, TestUtils }
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.concurrent.duration._
import scala.concurrent.Await

/** INTERNAL API - not (yet?) ready for public consuption */
private[akka] trait IntegrationRoutingSpec extends WordSpecLike with Matchers with BeforeAndAfterAll
  with Directives with RequestBuilding
  with ScalaFutures with IntegrationPatience {

  implicit val system = ActorSystem(AkkaSpec.getCallerName(getClass))
  implicit val mat = ActorMaterializer()
  import system.dispatcher

  override protected def afterAll(): Unit = {
    Await.ready(system.terminate(), 3.seconds)
  }

  implicit class DSL(request: HttpRequest) {
    def ~!>(route: Route) = new Prepped(request, route)
  }

  final case class Prepped(request: HttpRequest, route: Route)

  implicit class Checking(p: Prepped) {
    def ~!>(checking: HttpResponse ⇒ Unit) = {
      val (_, host, port) = TestUtils.temporaryServerHostnameAndPort()
      val binding = Http().bindAndHandle(p.route, host, port)

      try {
        val targetUri = p.request.uri.withHost(host).withPort(port).withScheme("http")
        val response = Http().singleRequest(p.request.withUri(targetUri)).futureValue
        checking(response)
      } finally binding.flatMap(_.unbind()).futureValue
    }
  }

}
