/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.StatusCodes
import akka.stream.scaladsl.Sink
import akka.testkit.TestActors
import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

import scala.language.postfixOps
import scala.concurrent.{ ExecutionContext, Future }

class HttpServerExampleSpec extends WordSpec with Matchers
  with CompileOnlySpec {

  // never actually called
  val log: LoggingAdapter = null

  "binding-example" in compileOnlySpec {
    //#binding-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] =
      Http().bind(interface = "localhost", port = 8080)
    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection => // foreach materializes the source
        println("Accepted new connection from " + connection.remoteAddress)
        // ... and then actually handle the connection
      }).run()
    //#binding-example
  }

  "binding-failure-high-level-example" in compileOnlySpec {
    //#binding-failure-high-level-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.Http.ServerBinding
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future

    object WebServer {
      def main(args: Array[String]) {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future onFailure in the end
        implicit val executionContext = system.dispatcher

        val handler = get {
          complete("Hello world!")
        }

        // let's say the OS won't allow us to bind to 80.
        val (host, port) = ("localhost", 80)
        val bindingFuture: Future[ServerBinding] =
          Http().bindAndHandle(handler, host, port)

        bindingFuture.onFailure {
          case ex: Exception =>
            log.error(ex, "Failed to bind to {}:{}!", host, port)
        }
      }
    }
    //#binding-failure-high-level-example
  }

  // mock values:
  val handleConnections = {
    import akka.stream.scaladsl.Sink
    Sink.ignore.mapMaterializedValue(_ => Future.failed(new Exception("")))
  }

  "binding-failure-handling" in compileOnlySpec {
    //#binding-failure-handling
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.Http.ServerBinding
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // needed for the future onFailure in the end
    implicit val executionContext = system.dispatcher

    // let's say the OS won't allow us to bind to 80.
    val (host, port) = ("localhost", 80)
    val serverSource = Http().bind(host, port)

    val bindingFuture: Future[ServerBinding] = serverSource
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()

    bindingFuture.onFailure {
      case ex: Exception =>
        log.error(ex, "Failed to bind to {}:{}!", host, port)
    }
    //#binding-failure-handling
  }

  object MyExampleMonitoringActor {
    def props = TestActors.echoActorProps
  }

  "incoming-connections-source-failure-handling" in compileOnlySpec {
    //#incoming-connections-source-failure-handling
    import akka.actor.ActorSystem
    import akka.actor.ActorRef
    import akka.http.scaladsl.Http
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    import Http._
    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)

    val failureMonitor: ActorRef = system.actorOf(MyExampleMonitoringActor.props)

    val reactToTopLevelFailures = Flow[IncomingConnection]
      .watchTermination()((_, termination) => termination.onFailure {
        case cause => failureMonitor ! cause
      })

    serverSource
      .via(reactToTopLevelFailures)
      .to(handleConnections) // Sink[Http.IncomingConnection, _]
      .run()
    //#incoming-connections-source-failure-handling
  }

  "connection-stream-failure-handling" in compileOnlySpec {
    //#connection-stream-failure-handling
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val (host, port) = ("localhost", 8080)
    val serverSource = Http().bind(host, port)

    val reactToConnectionFailure = Flow[HttpRequest]
      .recover[HttpRequest] {
        case ex =>
          // handle the failure somehow
          throw ex
      }

    val httpEcho = Flow[HttpRequest]
      .via(reactToConnectionFailure)
      .map { request =>
        // simple streaming (!) "echo" response:
        HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, request.entity.dataBytes))
      }

    serverSource
      .runForeach { con =>
        con.handleWith(httpEcho)
      }
    //#connection-stream-failure-handling
  }

  "full-server-example" in compileOnlySpec {
    //#full-server-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Sink

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val serverSource = Http().bind(interface = "localhost", port = 8080)

    val requestHandler: HttpRequest => HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
        HttpResponse(entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
        HttpResponse(entity = "PONG!")

      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
        sys.error("BOOM!")

      case r: HttpRequest =>
        r.discardEntityBytes() // important to drain incoming HTTP Entity stream
        HttpResponse(404, entity = "Unknown resource!")
    }

    val bindingFuture: Future[Http.ServerBinding] =
      serverSource.to(Sink.foreach { connection =>
        println("Accepted new connection from " + connection.remoteAddress)

        connection handleWithSyncHandler requestHandler
        // this is equivalent to
        // connection handleWith { Flow[HttpRequest] map requestHandler }
      }).run()
    //#full-server-example
  }

  "low-level-server-example" in compileOnlySpec {
    //#low-level-server-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.HttpMethods._
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import scala.io.StdIn

    object WebServer {

      def main(args: Array[String]) {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future map/flatmap in the end
        implicit val executionContext = system.dispatcher

        val requestHandler: HttpRequest => HttpResponse = {
          case HttpRequest(GET, Uri.Path("/"), _, _, _) =>
            HttpResponse(entity = HttpEntity(
              ContentTypes.`text/html(UTF-8)`,
              "<html><body>Hello world!</body></html>"))

          case HttpRequest(GET, Uri.Path("/ping"), _, _, _) =>
            HttpResponse(entity = "PONG!")

          case HttpRequest(GET, Uri.Path("/crash"), _, _, _) =>
            sys.error("BOOM!")

          case r: HttpRequest =>
            r.discardEntityBytes() // important to drain incoming HTTP Entity stream
            HttpResponse(404, entity = "Unknown resource!")
        }

        val bindingFuture = Http().bindAndHandleSync(requestHandler, "localhost", 8080)
        println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
        StdIn.readLine() // let it run until user presses return
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done

      }
    }
    //#low-level-server-example
  }

  // format: OFF

  "high-level-server-example" in compileOnlySpec {
    //#high-level-server-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer
    import scala.io.StdIn

    object WebServer {
      def main(args: Array[String]) {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future flatMap/onComplete in the end
        implicit val executionContext = system.dispatcher

        val route =
          get {
            pathSingleSlash {
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,"<html><body>Hello world!</body></html>"))
            } ~
              path("ping") {
                complete("PONG!")
              } ~
              path("crash") {
                sys.error("BOOM!")
              }
          }

        // `route` will be implicitly converted to `Flow` using `RouteResult.route2HandlerFlow`
        val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
        println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
        StdIn.readLine() // let it run until user presses return
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done
      }
    }
    //#high-level-server-example
  }

  "minimal-routing-example" in compileOnlySpec {
    //#minimal-routing-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer
    import scala.io.StdIn

    object WebServer {
      def main(args: Array[String]) {

        implicit val system = ActorSystem("my-system")
        implicit val materializer = ActorMaterializer()
        // needed for the future flatMap/onComplete in the end
        implicit val executionContext = system.dispatcher

        val route =
          path("hello") {
            get {
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
            }
          }

        val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

        println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
        StdIn.readLine() // let it run until user presses return
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done
      }
    }
    //#minimal-routing-example
  }

  "long-routing-example" in compileOnlySpec {
    //#long-routing-example
    import akka.actor.{ActorRef, ActorSystem}
    import akka.http.scaladsl.coding.Deflate
    import akka.http.scaladsl.marshalling.ToResponseMarshaller
    import akka.http.scaladsl.model.StatusCodes.MovedPermanently
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
    import akka.pattern.ask
    import akka.stream.ActorMaterializer
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
      path("oldApi" / Remaining) { pathRest =>
        redirect("http://oldapi.example.com/" + pathRest, MovedPermanently)
      }
    }
    //#long-routing-example
  }

  "stream random numbers" in compileOnlySpec {
    //#stream-random-numbers
    import akka.actor.ActorSystem
    import akka.stream.scaladsl._
    import akka.util.ByteString
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.{HttpEntity, ContentTypes}
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer
    import scala.util.Random
    import scala.io.StdIn

    object WebServer {

      def main(args: Array[String]) {

        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future flatMap/onComplete in the end
        implicit val executionContext = system.dispatcher

        // streams are re-usable so we can define it here
        // and use it for every request
        val numbers = Source.fromIterator(() =>
          Iterator.continually(Random.nextInt()))

        val route =
          path("random") {
            get {
              complete(
                HttpEntity(
                  ContentTypes.`text/plain(UTF-8)`,
                  // transform each number to a chunk of bytes
                  numbers.map(n => ByteString(s"$n\n"))
                )
              )
            }
          }

        val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
        println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
        StdIn.readLine() // let it run until user presses return
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done
      }
    }
    //#stream-random-numbers
  }

  "interact with an actor" in compileOnlySpec {
    //#actor-interaction
    import akka.actor.{Actor, ActorSystem, Props}
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model.StatusCodes
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import akka.pattern.ask
    import akka.stream.ActorMaterializer
    import akka.util.Timeout
    import spray.json.DefaultJsonProtocol._
    import scala.concurrent.duration._
    import scala.io.StdIn

    object WebServer {

      case class Bid(userId: String, bid: Int)
      case object GetBids
      case class Bids(bids: List[Bid])

      class Auction extends Actor {
        def receive = {
          case Bid(userId, bid) => println(s"Bid complete: $userId, $bid")
          case _ => println("Invalid message")
        }
      }

      // these are from spray-json
      implicit val bidFormat = jsonFormat2(Bid)
      implicit val bidsFormat = jsonFormat1(Bids)

      def main(args: Array[String]) {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future flatMap/onComplete in the end
        implicit val executionContext = system.dispatcher

        val auction = system.actorOf(Props[Auction], "auction")

        val route =
          path("auction") {
            put {
              parameter("bid".as[Int], "user") { (bid, user) =>
                // place a bid, fire-and-forget
                auction ! Bid(user, bid)
                complete((StatusCodes.Accepted, "bid placed"))
              }
            }
            get {
              implicit val timeout: Timeout = 5.seconds

              // query the actor for the current auction state
              val bids: Future[Bids] = (auction ? GetBids).mapTo[Bids]
              complete(bids)
            }
          }

        val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
        println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
        StdIn.readLine() // let it run until user presses return
        bindingFuture
          .flatMap(_.unbind()) // trigger unbinding from the port
          .onComplete(_ => system.terminate()) // and shutdown when done

      }
    }
    //#actor-interaction
  }

  "consume entity using entity directive" in compileOnlySpec {
    //#consume-entity-directive
    import akka.actor.ActorSystem
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import akka.stream.ActorMaterializer
    import spray.json.DefaultJsonProtocol._

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    final case class Bid(userId: String, bid: Int)

    // these are from spray-json
    implicit val bidFormat = jsonFormat2(Bid)

    val route =
      path("bid") {
        put {
          entity(as[Bid]) { bid =>
            // incoming entity is fully consumed and converted into a Bid
            complete("The bid was: " + bid)
          }
        }
      }
    //#consume-entity-directive
  }

  "consume entity using raw dataBytes to file" in compileOnlySpec {
    //#consume-raw-dataBytes
    import akka.actor.ActorSystem
    import akka.stream.scaladsl.FileIO
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer
    import java.io.File

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractDataBytes { bytes =>
            val finishedWriting = bytes.runWith(FileIO.toPath(new File("/tmp/example.out").toPath))

            // we only want to respond once the incoming data has been handled:
            onComplete(finishedWriting) { ioResult =>
              complete("Finished writing data: " + ioResult)
            }
          }
        }
      }
    //#consume-raw-dataBytes
  }

  "drain entity using request#discardEntityBytes" in compileOnlySpec {
    //#discard-discardEntityBytes
    import akka.actor.ActorSystem
    import akka.http.scaladsl.server.Directives._
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.model.HttpRequest

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractRequest { r: HttpRequest =>
            val finishedWriting = r.discardEntityBytes().future

            // we only want to respond once the incoming data has been handled:
            onComplete(finishedWriting) { done =>
              complete("Drained all data from connection... (" + done + ")")
            }
          }
        }
      }
    //#discard-discardEntityBytes
  }

  "discard entity manually" in compileOnlySpec {
    //#discard-close-connections
    import akka.actor.ActorSystem
    import akka.stream.scaladsl.Sink
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.headers.Connection
    import akka.stream.ActorMaterializer

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      (put & path("lines")) {
        withoutSizeLimit {
          extractDataBytes { data =>
            // Closing connections, method 1 (eager):
            // we deem this request as illegal, and close the connection right away:
            data.runWith(Sink.cancelled) // "brutally" closes the connection

            // Closing connections, method 2 (graceful):
            // consider draining connection and replying with `Connection: Close` header
            // if you want the client to close after this request/reply cycle instead:
            respondWithHeader(Connection("close"))
            complete(StatusCodes.Forbidden -> "Not allowed!")
          }
        }
      }
    //#discard-close-connections
  }


}
