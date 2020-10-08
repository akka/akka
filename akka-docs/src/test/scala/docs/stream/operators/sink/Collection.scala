/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sink

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Sink, Source }

import scala.concurrent.{ ExecutionContextExecutor, Future }

object Collection {
  implicit val system: ActorSystem = ???
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  def collectionExample: Future[Unit] = {
    //#collection
    val source = Source(1 to 100)
    val result: Future[List[Int]] = source.runWith(Sink.collection[Int, List[Int]])
    result.map(println)
    //#collection
  }
}
