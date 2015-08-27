/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl

import akka.actor.ActorSystem
import org.scalatest.{ Matchers, WordSpec }

class HttpClientExampleSpec extends WordSpec with Matchers {

  "outgoing-connection-example" in {
    pending // compile-time only test
    //#outgoing-connection-example
    import scala.concurrent.Future
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.Http

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
      Http().outgoingConnection("akka.io")
    val responseFuture: Future[HttpResponse] =
      Source.single(HttpRequest(uri = "/"))
        .via(connectionFlow)
        .runWith(Sink.single)
    //#outgoing-connection-example
  }

  "host-level-example" in {
    pending // compile-time only test
    //#host-level-example
    import scala.concurrent.Future
    import scala.util.Try
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.Http

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    // construct a pool client flow with context type `Int`
    val poolClientFlow = Http().cachedHostConnectionPool[Int]("akka.io")
    val responseFuture: Future[(Try[HttpResponse], Int)] =
      Source.single(HttpRequest(uri = "/") -> 42)
        .via(poolClientFlow)
        .runWith(Sink.single)
    //#host-level-example
  }

  "single-request-example" in {
    pending // compile-time only test
    //#single-request-example
    import scala.concurrent.Future
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.Http

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "http://akka.io"))
    //#single-request-example
  }

}
