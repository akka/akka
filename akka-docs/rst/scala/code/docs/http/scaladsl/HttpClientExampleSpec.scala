/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl

import akka.Done
import akka.actor.{ ActorLogging, ActorSystem }
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.HttpMessage.DiscardedEntity
import akka.stream.{ IOResult, Materializer }
import akka.stream.scaladsl.{ Framing, Sink }
import akka.util.ByteString
import docs.CompileOnlySpec
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.{ ExecutionContextExecutor, Future }

class HttpClientExampleSpec extends WordSpec with Matchers with CompileOnlySpec {

  "manual-entity-consume-example-1" in compileOnlySpec {
    //#manual-entity-consume-example-1
    import java.io.File
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl.Framing
    import akka.stream.scaladsl.FileIO
    import akka.http.scaladsl.model._

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
    import java.io.File
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.model._
    import scala.concurrent.duration._

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
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.model._

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    val response1: HttpResponse = ??? // obtained from an HTTP call (see examples below)

    val discarded: DiscardedEntity = response1.discardEntityBytes()
    discarded.future.onComplete { case done => println("Entity discarded completely!") }

    //#manual-entity-discard-example-1
  }
  "manual-entity-discard-example-2" in compileOnlySpec {
    import akka.actor.ActorSystem
    import akka.stream.ActorMaterializer
    import akka.http.scaladsl.model._

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    //#manual-entity-discard-example-2
    val response1: HttpResponse = ??? // obtained from an HTTP call (see examples below)

    val discardingComplete: Future[Done] = response1.entity.dataBytes.runWith(Sink.ignore)
    discardingComplete.onComplete { case done => println("Entity discarded completely!") }
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
        val responseFuture: Future[HttpResponse] =
          Source.single(HttpRequest(uri = "/"))
            .via(connectionFlow)
            .runWith(Sink.head)

        responseFuture.andThen {
          case Success(_) => println("request succeded")
          case Failure(_) => println("request failed")
        }.andThen {
          case _ => system.terminate()
        }
      }
    }
    //#outgoing-connection-example
  }

  "host-level-example" in compileOnlySpec {
    //#host-level-example
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.scaladsl._

    import scala.concurrent.Future
    import scala.util.Try

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()
    // construct a pool client flow with context type `Int`
    val poolClientFlow = Http().cachedHostConnectionPool[Int]("akka.io")
    val responseFuture: Future[(Try[HttpResponse], Int)] =
      Source.single(HttpRequest(uri = "/") -> 42)
        .via(poolClientFlow)
        .runWith(Sink.head)
    //#host-level-example
  }

  "single-request-example" in compileOnlySpec {
    //#single-request-example
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer

    import scala.concurrent.Future
    import scala.util.{ Failure, Success }

    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "http://akka.io"))
    //#single-request-example
  }

  "single-request-in-actor-example" in compileOnlySpec {
    //#single-request-in-actor-example
    import akka.actor.Actor
    import akka.http.scaladsl.Http
    import akka.http.scaladsl.model._
    import akka.stream.ActorMaterializer
    import akka.stream.ActorMaterializerSettings

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
          log.info("Got response, body: " + entity.dataBytes.runFold(ByteString(""))(_ ++ _))
        case HttpResponse(code, _, _, _) =>
          log.info("Request failed, response code: " + code)
      }

    }
    //#single-request-in-actor-example
  }

}
