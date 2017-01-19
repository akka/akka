/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ UnacceptedResponseContentTypeRejection, UnsupportedRequestContentTypeRejection }
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec
import org.scalatest.{ FlatSpec, WordSpec }

import scala.concurrent.Future

class JsonStreamingFullExamples extends WordSpec {

  "compile only spec" in {}

  //#custom-content-type
  import akka.NotUsed
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
  import akka.http.scaladsl.model.{ HttpEntity, StatusCodes, _ }
  import akka.http.scaladsl.server.Directives._
  import akka.stream.ActorMaterializer
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller, ToResponseMarshaller }
  import akka.http.scaladsl.model.TransferEncodings.gzip
  import akka.http.scaladsl.model.headers.{ HttpEncoding, HttpEncodings }
  import akka.http.scaladsl.model.ws.{ Message, TextMessage }
  import akka.stream.scaladsl.{ Flow, Source }
  import akka.util.ByteString
  import spray.json.DefaultJsonProtocol
  import spray.json.DefaultJsonProtocol._

  import scala.concurrent.Future
  import scala.io.StdIn
  import scala.util.Random

  final case class User(name: String, id: String)

  trait UserProtocol extends DefaultJsonProtocol {

    import spray.json._

    implicit val userFormat = jsonFormat2(User)

    val `vnd.example.api.v1+json` =
      MediaType.applicationWithFixedCharset("vnd.example.api.v1+json", HttpCharsets.`UTF-8`)
    val ct = ContentType.apply(`vnd.example.api.v1+json`)

    implicit def userMarshaller: ToEntityMarshaller[User] = Marshaller.oneOf(
      Marshaller.withFixedContentType(`vnd.example.api.v1+json`) { organisation ⇒
        HttpEntity(`vnd.example.api.v1+json`, organisation.toJson.compactPrint)
      })
  }

  object ApiServer extends App with UserProtocol {
    implicit val system = ActorSystem("api")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()
      .withContentType(ct)
      .withParallelMarshalling(parallelism = 10, unordered = false)

    // (fake) async database query api
    def dummyUser(id: String) = User(s"User $id", id.toString)

    def fetchUsers(): Source[User, NotUsed] = Source.fromIterator(() ⇒ Iterator.fill(10000) {
      val id = Random.nextInt()
      dummyUser(id.toString)
    })

    val route =
      pathPrefix("users") {
        get {
          complete(fetchUsers())
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine()
    bindingFuture.flatMap(_.unbind()).onComplete(_ ⇒ system.terminate())
  }

  //#custom-content-type
}
