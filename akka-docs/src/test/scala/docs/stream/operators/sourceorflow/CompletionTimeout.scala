/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, Sink, Source }

import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }
import scala.concurrent.{ ExecutionContextExecutor, Future }

object CompletionTimeout {
  implicit val system: ActorSystem = ???
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  def completionTimeoutExample: Future[Int] = {
    //#completionTimeout
    val source = Source(1 to 10000)
    val flow = Flow[Int].map(number => number * number).completionTimeout(FiniteDuration(10, MILLISECONDS))
    source.via(flow).runWith(Sink.reduce((acc, element) => acc + element))
    //#completionTimeout
  }
}
