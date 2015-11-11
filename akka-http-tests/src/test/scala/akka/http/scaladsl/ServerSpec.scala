package akka.http.scaladsl

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.scalatest.{ Matchers, WordSpec }

import scala.util._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

class ServerSpec extends WordSpec with Matchers with Directives {

  "server" should {
    "not accept any more connections" when {
      "unbound" in {
        implicit val sys = ActorSystem("ClientSpec")
        implicit val mat = ActorMaterializer()
        import sys.dispatcher

        val binding = Await.result(Http().bindAndHandle(
          path("test") {
            complete("ok")
          }, "127.0.0.1", 0), 1.second)

        val hostname = binding.localAddress.getHostString
        val port = binding.localAddress.getPort

        val pool = Http().cachedHostConnectionPool[Int](hostname, port)
        val request = HttpRequest(uri = s"http://$hostname:$port/test")

        for (i ← 1 to 16) {
          Await.result(clientRequest(request, pool), 2.second) shouldBe "ok"
        }

        Await.ready(binding.unbind(), 1.second)

        for (i ← 1 to 16) {
          Await.result(clientRequest(request, pool), 2.second) shouldBe "fail"
        }
      }
    }
  }

  def clientRequest(request: HttpRequest, pool: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), Http.HostConnectionPool])(implicit mat: ActorMaterializer, ec: ExecutionContext) =
    Source.single(request -> 42)
      .via(pool)
      .mapAsync(4) {
        case (Success(r), _) ⇒ Unmarshal(r).to[String]
        case (Failure(_), _) ⇒ Future.successful("fail")
      }
      .runWith(Sink.head)

}

