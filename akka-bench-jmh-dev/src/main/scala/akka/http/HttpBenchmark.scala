/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try
import com.typesafe.config.ConfigFactory

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.SampleTime))
class HttpBenchmark {

  val config = ConfigFactory.parseString(
    """
      akka {
        loglevel = "ERROR"
      }""".stripMargin).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("HttpBenchmark", config)
  implicit val materializer = ActorMaterializer()

  var binding: ServerBinding = _
  var request: HttpRequest = _
  var pool: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), _] = _

  @Setup
  def setup() = {
    val route = {
      path("test") {
        get {
          complete("ok")
        }
      }
    }

    binding = Await.result(Http().bindAndHandle(route, "127.0.0.1", 0), 1.second)
    request = HttpRequest(uri = s"http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/test")
    pool = Http().cachedHostConnectionPool[Int](binding.localAddress.getHostString, binding.localAddress.getPort)
  }

  @TearDown
  def shutdown() = {
    Await.ready(Http().shutdownAllConnectionPools(), 1.second)
    binding.unbind()
    system.shutdown()
    system.awaitTermination()
  }

  @Benchmark
  def single_request() = {
    import system.dispatcher
    val response = Await.result(Http().singleRequest(request), 1.second)
    Await.result(Unmarshal(response.entity).to[String], 1.second)
  }

  @Benchmark
  def single_request_pool() = {
    import system.dispatcher
    val (response, id) = Await.result(Source.single(HttpRequest(uri = "/test") -> 42).via(pool).runWith(Sink.head), 1.second)
    Await.result(Unmarshal(response.get.entity).to[String], 1.second)
  }
}
