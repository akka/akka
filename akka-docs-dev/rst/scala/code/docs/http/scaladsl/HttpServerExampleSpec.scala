/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl
/*
import scala.concurrent.Future
import org.scalatest.{ WordSpec, Matchers }
import akka.actor.ActorSystem

class HttpServerExampleSpec extends WordSpec with Matchers {

  "binding-example" in {
    import akka.stream.ActorFlowMaterializer
    import akka.stream.scaladsl._
    import akka.http.scaladsl.Http

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().bind(interface = "localhost", port = 8080)
    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection => // foreach materializes the source
        println("Accepted new connection from " + connection.remoteAddress)
        // ... and then actually handle the connection
      }).run()
  }

  "full-server-example" in {
    import akka.stream.ActorFlowMaterializer
    import akka.stream.scaladsl.Sink
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val serverSource = Http().bind(interface = "localhost", port = 8080)

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(MediaTypes.`text/html`,
          "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
        HttpResponse(entity = "PONG!")

      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
        sys.error("BOOM!")

      case _: HttpRequest =>
        HttpResponse(404, entity = "Unknown resource!")
    }

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)

        connection handleWithSyncHandler requestHandler
        // this is equivalent to
        // connection handleWith { Flow[HttpRequest] map requestHandler }
      }).run()
  }

  "low-level-server-example" in {
    import akka.stream.ActorFlowMaterializer
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(MediaTypes.`text/html`,
          "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
        HttpResponse(entity = "PONG!")

      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
        sys.error("BOOM!")

      case _: HttpRequest =>
        HttpResponse(404, entity = "Unknown resource!")
    }

    Http().bindAndHandleSync(requestHandler, "localhost", 8080)
  }

  // format: OFF

  "high-level-server-example" in {
    import akka.stream.ActorFlowMaterializer
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

    implicit val system = ActorSystem()
    implicit val materializer = ActorFlowMaterializer()

    val route =
      get {
        pathSingleSlash {
          complete {
            <html>
              <body>Hello world!</body>
            </html>
          }
        } ~
        path("ping") {
          complete("PONG!")
        } ~
        path("crash") {
          sys.error("BOOM!")
        }
      }

    Http().bindAndHandle(route, "localhost", 8080)
  }

  "minimal-routing-example" in {
    import akka.stream.ActorFlowMaterializer
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._

    object Main extends App {
      implicit val system = ActorSystem("my-system")
      implicit val materializer = ActorFlowMaterializer()

      val route =
        path("hello") {
          get {
            complete {
              <h1>Say hello to spray</h1>
            }
          }
        }

      val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

      println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
      Console.readLine()

      import system.dispatcher // for the future transformations
      bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
    }
  }

  "long-routing-example" in {
    import akka.actor.ActorRef
    import akka.util.Timeout
    import akka.pattern.ask
    import akka.http.scaladsl.marshalling.ToResponseMarshaller
    import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
    import akka.http.scaladsl.model.StatusCodes.MovedPermanently
    import akka.http.scaladsl.coding.Deflate
    import akka.http.scaladsl.server.Directives._

    // types used by the API routes
    type Money = Double // only for demo purposes, don't try this at home!
    type TransactionResult = String
    case class User(name: String)
    case class Order(email: String, amount: Money)
    case class Update(order: Order)
    case class OrderItem(i: Int, os: Option[String], s: String)
    implicit val orderUM: FromRequestUnmarshaller[Order] = ???
    implicit val orderM: ToResponseMarshaller[Seq[Order]] = ???
    implicit val timeout: Timeout = ??? // for actor asks

    val route = {
      path("orders") {
        authenticateBasic(realm = "admin area", myAuthenticator) { user =>
          get {
            encodeResponseWith(Deflate) {
              complete {
                // marshal custom object with in-scope marshaller
                retrieveOrdersFromDB
              }
            }
          } ~
          post {
            // decompress gzipped or deflated requests if required
            decodeRequest {
              // unmarshal with in-scope unmarshaller
              entity(as[Order]) { order =>
                complete {
                  // ... write order to DB
                  "Order received"
                }
              }
            }
          }
        }
      } ~
      // extract URI path element as Int
      pathPrefix("order" / IntNumber) { orderId =>
        pathEnd {
          (put | parameter('method ! "put")) {
            // form extraction from multipart or www-url-encoded forms
            formFields('email, 'total.as[Money]).as(Order) { order =>
              complete {
                // complete with serialized Future result
                (myDbActor ? Update(order)).mapTo[TransactionResult]
              }
            }
          } ~
          get {
            // debugging helper
            logRequest("GET-ORDER") {
              // use in-scope marshaller to create completer function
              completeWith(instanceOf[Order]) { completer =>
                // custom
                processOrderRequest(orderId, completer)
              }
            }
          }
        } ~
        path("items") {
          get {
            // parameters to case class extraction
            parameters('size.as[Int], 'color ?, 'dangerous ? "no")
              .as(OrderItem) { orderItem =>
                // ... route using case class instance created from
                // required and optional query parameters
                complete("") // hide
              }
          }
        }
      } ~
      pathPrefix("documentation") {
        // optionally compresses the response with Gzip or Deflate
        // if the client accepts compressed responses
        encodeResponse {
          // serve up static content from a JAR resource
          getFromResourceDirectory("docs")
        }
      } ~
      path("oldApi" / Rest) { pathRest =>
        redirect("http://oldapi.example.com/" + pathRest, MovedPermanently)
      }
    }

    // backend entry points
    def myAuthenticator: Authenticator[User] = ???
    def retrieveOrdersFromDB: Seq[Order] = ???
    def myDbActor: ActorRef = ???
    def processOrderRequest(id: Int, complete: Order => Unit): Unit = ???
  }
}
*/