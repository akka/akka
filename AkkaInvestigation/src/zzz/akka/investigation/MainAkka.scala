package zzz.akka.investigation

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, ExecutionContext}

object MainAkka {
  val pool = Executors.newCachedThreadPool()
  implicit val ec = ExecutionContext.fromExecutorService(pool)

  def main(args: Array[String]) {
    val future = Future {
      "Fibonacci Numbers"
    }
    val result = Await.result(future, 1.seconds)
    println(result)
    pool.shutdown()
  }

}