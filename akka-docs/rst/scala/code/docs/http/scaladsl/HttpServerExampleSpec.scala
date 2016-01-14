/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Sink }
import akka.stream.stage.{ Context, PushStage }
import akka.testkit.TestActors
import org.scalatest.{ Matchers, WordSpec }
import scala.language.postfixOps

import scala.concurrent.{ ExecutionContext, Future }

class HttpServerExampleSpec extends WordSpec with Matchers {

  // never actually called
  val log: LoggingAdapter = null

  def compileOnlySpec(body: => Unit) = ()

  "binding-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().bind(interface = "localhost", port = 8080)
    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection => // foreach materializes the source
        println("Accepted new connection from " + connection.remoteAddress)
        // ... and then actually handle the connection
      }).run()
  }

  "binding-failure-high-level-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    val handler = get {
      complete("Hello world!")
    }

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val bindingFuture: Future[ServerBinding] =
      Http().bindAndHandle(handler, host, port)

    bindingFuture onFailure {
      case ex: Exception =>
        log.error(ex, "Failed to bind to {}:{}!", host, port)
    }

  }

  // mock values:
  val handleConnections: Sink[Http.IncomingConnection, Future[Http.ServerBinding]] =
    Sink.ignore.mapMaterializedValue(_ => Future.failed(new Exception("")))

  "binding-failure-handling" in compileOnlySpec {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val serverSource = Http().bind(host, port)

    val bindingFuture: Future[ServerBinding] = serverSource
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()

    bindingFuture onFailure {
      case ex: Exception =>
        log.error(ex, "Failed to bind to {}:{}!", host, port)
    }
  }

  object MyExampleMonitoringActor {
    def props = TestActors.echoActorProps
  }

  "incoming-connections-source-failure-handling" in compileOnlySpec {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    import Http._
    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)

    val failureMonitor: ActorRef = system.actorOf(MyExampleMonitoringActor.props)

    val reactToTopLevelFailures = Flow[IncomingConnection]
      .transform { () =>
        new PushStage[IncomingConnection, IncomingConnection] {
          override def onPush(elem: IncomingConnection, ctx: Context[IncomingConnection]) =
            ctx.push(elem)

          override def onUpstreamFailure(cause: Throwable, ctx: Context[IncomingConnection]) = {
            failureMonitor ! cause
            super.onUpstreamFailure(cause, ctx)
          }
        }
      }

    serverSource
      .via(reactToTopLevelFailures)
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()
  }

  "connection-stream-failure-handling" in compileOnlySpec {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val ec = system.dispatcher

    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)

    val reactToConnectionFailure = Flow[HttpRequest]
      .transform { () =>
        new PushStage[HttpRequest, HttpRequest] {
          override def onPush(elem: HttpRequest, ctx: Context[HttpRequest]) =
            ctx.push(elem)

          override def onUpstreamFailure(cause: Throwable, ctx: Context[HttpRequest]) = {
            // handle the failure somehow
            super.onUpstreamFailure(cause, ctx)
          }
        }
      }

    val httpEcho = Flow[HttpRequest]
      .via(reactToConnectionFailure)
      .map { request =>
        // simple text "echo" response:
        HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, request.entity.dataBytes))
      }

    serverSource
      .runForeach { con =>
        con.handleWith(httpEcho)
      }
  }

  "full-server-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Sink

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val serverSource = Http().bind(interface = "localhost", port = 8080)

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
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

  "low-level-server-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
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

  "high-level-server-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

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

    // `route` will be implicitly converted to `Flow` using `RouteResult.route2HandlerFlow`
    Http().bindAndHandle(route, "localhost", 8080)
  }

  "minimal-routing-example" in compileOnlySpec {
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer

    object Main extends App {
      implicit val system = ActorSystem("my-system")
      implicit val materializer = ActorMaterializer()
      implicit val ec = system.dispatcher

      val route =
        path("hello") {
          get {
            complete {
              <h1>Say hello to akka-http</h1>
            }
          }
        }

      val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

      println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
      Console.readLine() // for the future transformations
      bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
    }
  }

  "long-routing-example" in compileOnlySpec {
    //#long-routing-example
    import akka.actor.ActorRef
    import akka.http.scaladsl.coding.Deflate
    import akka.http.scaladsl.marshalling.ToResponseMarshaller
    import akka.http.scaladsl.model.StatusCodes.MovedPermanently
    import akka.http.scaladsl.server.Directives._
    // TODO: these explicit imports are only needed in complex cases, like below; Also, not needed on Scala 2.11
    import akka.http.scaladsl.server.directives.ParameterDirectives.ParamMagnet
    import akka.http.scaladsl.server.directives.FormFieldDirectives.FieldMagnet
    import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
    import akka.pattern.ask
    import akka.util.Timeout

    // types used by the API routes
    type Money = Double // only for demo purposes, don't try this at home!
    type TransactionResult = String
    case class User(name: String)
    case class Order(email: String, amount: Money)
    case class Update(order: Order)
    case class OrderItem(i: Int, os: Option[String], s: String)

    // marshalling would usually be derived automatically using libraries
    implicit val orderUM: FromRequestUnmarshaller[Order] = ???
    implicit val orderM: ToResponseMarshaller[Order] = ???
    implicit val orderSeqM: ToResponseMarshaller[Seq[Order]] = ???
    implicit val timeout: Timeout = ??? // for actor asks
    implicit val ec: ExecutionContext = ???
    implicit val mat: ActorMaterializer = ???
    implicit val sys: ActorSystem = ???

    // backend entry points
    def myAuthenticator: Authenticator[User] = ???
    def retrieveOrdersFromDB: Seq[Order] = ???
    def myDbActor: ActorRef = ???
    def processOrderRequest(id: Int, complete: Order => Unit): Unit = ???

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
            formFields(('email, 'total.as[Money])).as(Order) { order =>
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
            parameters(('size.as[Int], 'color ?, 'dangerous ? "no"))
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
  }
}
