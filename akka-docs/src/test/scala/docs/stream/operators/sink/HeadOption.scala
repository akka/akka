/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sink

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Sink, Source }

import scala.concurrent.{ ExecutionContextExecutor, Future }

object HeadOption {
  implicit val system: ActorSystem = ???
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  def headOptionExample: Future[Unit] = {
    //#headoption
    val source = Source.empty
    val result: Future[Option[Int]] = source.runWith(Sink.headOption)
    result.map(println)
    //None
    //#headoption
  }
}
