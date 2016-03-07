/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http

import akka.http.scaladsl.model._
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

/**
 * Benchmark used to verify move to name-based extraction does not hurt preformance.
 * It does not allocate an Option thus it should be more optimal actually.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class HttpMessageMatchingBenchmark {

  val req = HttpRequest()
  val res = HttpResponse()

  @Benchmark
  def res_matching: HttpResponse = {
    res match {
      case r @ HttpResponse(status, headers, entity, protocol) => r
    }
  }

  @Benchmark
  def req_matching: HttpRequest = {
    req match {
      case r @ HttpRequest(method, uri, headers, entity, protocol) => r
    }
  }

}