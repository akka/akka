package docs.stream.operators.source

// #LazySource

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.actor.ActorSystem

object LazySource {
  implicit val system: ActorSystem = ???

  def fib(n: Int): Long = n match {
    case x if x < 0 =>
      throw new IllegalArgumentException("Only positive numbers allowed")
    case 0 | 1 => 1
    case _     => fib(n - 2) + fib(n - 1)
  }

  val lzSource: Source[() => Long, NotUsed] = Source((0 to 10).map(i => {
    lazy val r = fib(i)
    () =>
      r
  }))

  lzSource.runForeach(i => println(i()))
  // could print
  // 1
  // 1
  // 2
  // 3
  // 5
  // 8
  // 13
  // 21
  // 34
  // 55
  // 89
}

// #LazySource
