/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

class HttpClientExampleSpec extends WordSpec with Matchers with CompileOnlySpec {

  "manual-entity-consume-example-1" in compileOnlySpec {
    //#manual-entity-consume-example-1
    import java.io.File

    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.{ FileIO, Framing }
    import akka.util.ByteString

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    val response: HttpResponse = ???

    response.entity.dataBytes
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256))
      .map(transformEachLine)
      .runWith(FileIO.toPath(new File("/tmp/example.out").toPath))

    def transformEachLine(line: ByteString): ByteString = ???

    //#manual-entity-consume-example-1
  }

  "manual-entity-consume-example-2" in compileOnlySpec {
    //#manual-entity-consume-example-2
    import scala.concurrent.Future
    import scala.concurrent.duration._

    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.util.ByteString

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    case class ExamplePerson(name: String)
    def parse(line: ByteString): ExamplePerson = ???

    val response: HttpResponse = ???

    // toStrict to enforce all data be loaded into memory from the connection
    val strictEntity: Future[HttpEntity.Strict] = response.entity.toStrict(3.seconds)

    // while API remains the same to consume dataBytes, now they're in memory already:
    val transformedData: Future[ExamplePerson] =
      strictEntity flatMap { e =>
        e.dataBytes
          .runFold(ByteString.empty) { case (acc, b) => acc ++ b }
          .map(parse)
      }

    //#manual-entity-consume-example-2
  }

  "manual-entity-discard-example-1" in compileOnlySpec {
    //#manual-entity-discard-example-1
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model.HttpMessage.DiscardedEntity
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    val response1: HttpResponse = ??? // obtained from an HTTP call (see examples below)

    val discarded: DiscardedEntity = response1.discardEntityBytes()
    discarded.future.onComplete { done => println("Entity discarded completely!") }

    //#manual-entity-discard-example-1
  }
  "manual-entity-discard-example-2" in compileOnlySpec {
    import scala.concurrent.Future

    import akka.Done
    import akka.actor.ActorSystem
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Sink

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    //#manual-entity-discard-example-2
    val response1: HttpResponse = ??? // obtained from an HTTP call (see examples below)

    val discardingComplete: Future[Done] = response1.entity.dataBytes.runWith(Sink.ignore)
    discardingComplete.onComplete(done => println("Entity discarded completely!"))
    //#manual-entity-discard-example-2
  }

  "outgoing-connection-example" in compileOnlySpec {
    //#outgoing-connection-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    import scala.concurrent.Future
    import scala.util.{ Failure, Success }

    object WebClient {
      def main(args: Array[String]): Unit = {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        implicit val executionContext = system.dispatcher

        val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
          Http().outgoingConnection("akka.io")

        def dispatchRequest(request: HttpRequest): Future[HttpResponse] =
          // This is actually a bad idea in general. Even if the `connectionFlow` was instantiated only once above,
          // a new connection is opened every single time, `runWith` is called. Materialization (the `runWith` call)
          // and opening up a new connection is slow.
          //
          // The `outgoingConnection` API is very low-level. Use it only if you already have a `Source[HttpRequest]`
          // (other than Source.single) available that you want to use to run requests on a single persistent HTTP
          // connection.
          //
          // Unfortunately, this case is so uncommon, that we couldn't come up with a good example.
          //
          // In almost all cases it is better to use the `Http().singleRequest()` API instead.
          Source.single(request)
            .via(connectionFlow)
            .runWith(Sink.head)

        val responseFuture: Future[HttpResponse] = dispatchRequest(HttpRequest(uri = "/"))

        responseFuture.andThen {
          case Success(_) => println("request succeeded")
          case Failure(_) => println("request failed")
        }.andThen {
          case _ => system.terminate()
        }
      }
    }
    //#outgoing-connection-example
  }

  "host-level-queue-example" in compileOnlySpec {
    //#host-level-queue-example
    import scala.util.{ Failure, Success }
    import scala.concurrent.{ Future, Promise }

    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    import akka.stream.{ OverflowStrategy, QueueOfferResult }

    implicit val system = ActorSystem()
    import system.dispatcher // to get an implicit ExecutionContext into scope
    implicit val materializer = ActorMaterializer()

    val QueueSize = 10

    // This idea came initially from this blog post:
    // http://kazuhiro.github.io/scala/akka/akka-http/akka-streams/2016/01/31/connection-pooling-with-akka-http-and-source-queue.html
    val poolClientFlow = Http().cachedHostConnectionPool[Promise[HttpResponse]]("akka.io")
    val queue =
      Source.queue[(HttpRequest, Promise[HttpResponse])](QueueSize, OverflowStrategy.dropNew)
        .via(poolClientFlow)
        .toMat(Sink.foreach({
          case ((Success(resp), p)) => p.success(resp)
          case ((Failure(e), p))    => p.failure(e)
        }))(Keep.left)
        .run()

    def queueRequest(request: HttpRequest): Future[HttpResponse] = {
      val responsePromise = Promise[HttpResponse]()
      queue.offer(request -> responsePromise).flatMap {
        case QueueOfferResult.Enqueued    => responsePromise.future
        case QueueOfferResult.Dropped     => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
        case QueueOfferResult.Failure(ex) => Future.failed(ex)
        case QueueOfferResult.QueueClosed => Future.failed(new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later."))
      }
    }

    val responseFuture: Future[HttpResponse] = queueRequest(HttpRequest(uri = "/"))
    //#host-level-queue-example
  }

  "host-level-streamed-example" in compileOnlySpec {
    //#host-level-streamed-example
    import java.nio.file.{ Path, Paths }

    import scala.util.{ Failure, Success }
    import scala.concurrent.Future

    import akka.NotUsed
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    import akka.http.scaladsl.model.Multipart.FormData
    import akka.http.scaladsl.marshalling.Marshal

    implicit val system = ActorSystem()
    import system.dispatcher // to get an implicit ExecutionContext into scope
    implicit val materializer = ActorMaterializer()

    case class FileToUpload(name: String, location: Path)

    def filesToUpload(): Source[FileToUpload, NotUsed] =
      // This could even be a lazy/infinite stream. For this example we have a finite one:
      Source(List(
        FileToUpload("foo.txt", Paths.get("./foo.txt")),
        FileToUpload("bar.txt", Paths.get("./bar.txt")),
        FileToUpload("baz.txt", Paths.get("./baz.txt"))
      ))

    val poolClientFlow =
      Http().cachedHostConnectionPool[FileToUpload]("akka.io")

    def createUploadRequest(fileToUpload: FileToUpload): Future[(HttpRequest, FileToUpload)] = {
      val bodyPart =
        // fromPath will use FileIO.fromPath to stream the data from the file directly
        FormData.BodyPart.fromPath(fileToUpload.name, ContentTypes.`application/octet-stream`, fileToUpload.location)

      val body = FormData(bodyPart) // only one file per upload
      Marshal(body).to[RequestEntity].map { entity => // use marshalling to create multipart/formdata entity
        // build the request and annotate it with the original metadata
        HttpRequest(method = HttpMethods.POST, uri = "http://example.com/uploader", entity = entity) -> fileToUpload
      }
    }

    // you need to supply the list of files to upload as a Source[...]
    filesToUpload()
      // The stream will "pull out" these requests when capacity is available.
      // When that is the case we create one request concurrently
      // (the pipeline will still allow multiple requests running at the same time)
      .mapAsync(1)(createUploadRequest)
      // then dispatch the request to the connection pool
      .via(poolClientFlow)
      // report each response
      // Note: responses will not come in in the same order as requests. The requests will be run on one of the
      // multiple pooled connections and may thus "overtake" each other.
      .runForeach {
        case (Success(response), fileToUpload) =>
          // TODO: also check for response status code
          println(s"Result for file: $fileToUpload was successful: $response")
          response.discardEntityBytes() // don't forget this
        case (Failure(ex), fileToUpload) =>
          println(s"Uploading file $fileToUpload failed with $ex")
      }
    //#host-level-streamed-example
  }

  "single-request-example" in compileOnlySpec {
    //#single-request-example
    import akka.actor.ActorSystem
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future
    import scala.util.{ Failure, Success }

    object Client {
      def main(args: Array[String]): Unit = {
        implicit val system = ActorSystem()
        implicit val materializer = ActorMaterializer()
        // needed for the future flatMap/onComplete in the end
        implicit val executionContext = system.dispatcher

        val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = "http://akka.io"))

        responseFuture
          .onComplete {
            case Success(res) => println(res)
            case Failure(_)   => sys.error("something wrong")
          }
      }
    }
    //#single-request-example
  }

  "single-request-in-actor-example" in compileOnlySpec {
    //#single-request-in-actor-example
    import akka.actor.{ Actor, ActorLogging }
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
    import akka.util.ByteString

    class Myself extends Actor
      with ActorLogging {

      import akka.pattern.pipe
      import context.dispatcher

      final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

      val http = Http(context.system)

      override def preStart() = {
        http.singleRequest(HttpRequest(uri = "http://akka.io"))
          .pipeTo(self)
      }

      def receive = {
        case HttpResponse(StatusCodes.OK, headers, entity, _) =>
          entity.dataBytes.runFold(ByteString(""))(_ ++ _).foreach { body =>
            log.info("Got response, body: " + body.utf8String)
          }
        case resp @ HttpResponse(code, _, _, _) =>
          log.info("Request failed, response code: " + code)
          resp.discardEntityBytes()
      }

    }
    //#single-request-in-actor-example
  }

  "https-proxy-example-single-request" in compileOnlySpec {
    //#https-proxy-example-single-request
    import java.net.InetSocketAddress

    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.{ ClientTransport, Http }

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val proxyHost = "localhost"
    val proxyPort = 8888

    val httpsProxyTransport = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved(proxyHost, proxyPort))

    val settings = ConnectionPoolSettings(system).withTransport(httpsProxyTransport)
    Http().singleRequest(HttpRequest(uri = "https://google.com"), settings = settings)
    //#https-proxy-example-single-request
  }

  "https-proxy-example-single-request with auth" in compileOnlySpec {
    import java.net.InetSocketAddress

    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.{ ClientTransport, Http }

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val proxyHost = "localhost"
    val proxyPort = 8888

    //#auth-https-proxy-example-single-request
    import akka.http.scaladsl.model.headers

    val proxyAddress = InetSocketAddress.createUnresolved(proxyHost, proxyPort)
    val auth = headers.BasicHttpCredentials("proxy-user", "secret-proxy-pass-dont-tell-anyone")

    val httpsProxyTransport = ClientTransport.httpsProxy(proxyAddress, auth)

    val settings = ConnectionPoolSettings(system).withTransport(httpsProxyTransport)
    Http().singleRequest(HttpRequest(uri = "http://akka.io"), settings = settings)
    //#auth-https-proxy-example-single-request
  }

}
