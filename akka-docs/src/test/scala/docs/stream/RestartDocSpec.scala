/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream

import akka.NotUsed
import akka.stream.{ ActorMaterializer, KillSwitches }
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import docs.CompileOnlySpec

import scala.concurrent.duration._
import scala.concurrent._

class RestartDocSpec extends AkkaSpec with CompileOnlySpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  // Mock akka-http interfaces
  object Http {
    def apply() = this
    def singleRequest(req: HttpRequest) = Future.successful(())
  }
  case class HttpRequest(uri: String)
  case class Unmarshal(b: Any) {
    def to[T]: Future[T] = Promise[T]().future
  }
  case class ServerSentEvent()

  def doSomethingElse(): Unit = ()

  "Restart stages" should {

    "demonstrate a restart with backoff source" in compileOnlySpec {

      //#restart-with-backoff-source
      val restartSource = RestartSource.withBackoff(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2, // adds 20% "noise" to vary the intervals slightly
        maxRestarts = 20 // limits the amount of restarts to 20
      ) { () ⇒
        // Create a source from a future of a source
        Source.fromFutureSource {
          // Make a single request with akka-http
          Http().singleRequest(HttpRequest(
            uri = "http://example.com/eventstream"
          ))
            // Unmarshall it as a source of server sent events
            .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
        }
      }
      //#restart-with-backoff-source

      //#with-kill-switch
      val killSwitch = restartSource
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreach(event ⇒ println(s"Got event: $event")))(Keep.left)
        .run()

      doSomethingElse()

      killSwitch.shutdown()
      //#with-kill-switch
    }

  }
}
