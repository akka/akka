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
import akka.stream.Supervision
import akka.stream.ActorMaterializerSettings
import com.typesafe.config.ConfigFactory

class ClientSpec extends WordSpec with Matchers with Directives {

  val Hostname = "127.0.0.1"
  val Port = 12345

  "connection pool" should {
    "survive" when {
      "client is issuing requests" in {
        implicit val sys = ActorSystem("ClientSpec", ConfigFactory.parseString("""
          akka.loglevel = "DEBUG"
        """).withFallback(ConfigFactory.load))

        val decider: Supervision.Decider = {
          case ex ⇒ println(s"Error in stream: ${ex.getClass.getName}:${ex.getMessage}. Resuming stream."); Supervision.Resume
        }
        implicit val mat = ActorMaterializer(ActorMaterializerSettings(sys).withSupervisionStrategy(decider))

        val request = HttpRequest(uri = s"http://$Hostname:$Port/test")
        val stream = requests(request)

        Console.readLine()

        stream.cancel()
        Await.ready(Http().shutdownAllConnectionPools(), 1.second)
        sys.shutdown()
        sys.awaitTermination()
      }

      "server is handling requests" in {
        implicit val sys = ActorSystem("ClientSpec")
        implicit val mat = ActorMaterializer()

        val binding = Await.result(Http().bindAndHandle(
          path("test") {
            complete("ok")
          }, Hostname, Port), 1.second)

        Console.readLine()

        Await.ready(binding.unbind(), 1.second)
        sys.shutdown()
        sys.awaitTermination()
      }
    }
  }

  def requests(request: HttpRequest)(implicit sys: ActorSystem, mat: ActorMaterializer) = {
    import sys.dispatcher
    Source(1.second, 100.millis, "tick")
      .mapAsync(4)(_ ⇒ Http().singleRequest(request))
      .mapAsync(4)(Unmarshal(_).to[String])
      .toMat(Sink.foreach(println))(Keep.left)
      .run()
  }

  def requestsPool(request: HttpRequest)(implicit sys: ActorSystem, mat: ActorMaterializer) = {
    import sys.dispatcher
    val pool = Http().cachedHostConnectionPool[Int](Hostname, Port)
    Source(1.second, 1.second, "tick")
      .map(_ ⇒ request -> 42)
      .via(pool)
      .mapAsync(4) {
        case (Success(r), _) ⇒ Unmarshal(r).to[String]
        case (Failure(_), _) ⇒ Future.successful("fail")
      }
      .toMat(Sink.foreach(println))(Keep.left)
      .run()
  }

}
