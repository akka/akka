/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

/**
 *
 */
object CommonMapAsync {
  case class Event(sequenceNumber: Int)

  implicit val sys: ActorSystem = ActorSystem("mapAsync-stream")
  implicit val exCtx: ExecutionContextExecutor = sys.dispatcher
  implicit val timeout: Timeout = 3.seconds

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  val events: Source[Event, NotUsed] = //...
    // #mapasync-strict-order
    // #mapasync-concurrent
    // #mapasyncunordered
    Source.fromIterator(() => Iterator.from(1)).throttle(1, 50.millis).map { in =>
      Event(in)
    }

  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

  def eventHandler(event: Event): Future[Int] = {
    println(s"Processing event $event...")
    //...
    // #mapasync-strict-order
    // #mapasync-concurrent
    // #mapasyncunordered
    val result =
      if (Random.nextInt(5) == 0) {
        akka.pattern.after(500.millis)(Future.successful(event.sequenceNumber))
      } else {
        Future.successful(event.sequenceNumber)
      }
    result.map { x =>
      println(s"Completed processing $x")
      x
    }
    // #mapasync-strict-order
    // #mapasync-concurrent
    // #mapasyncunordered
  }
  // #mapasync-strict-order
  // #mapasync-concurrent
  // #mapasyncunordered

}

object MapAsyncStrictOrder extends App {
  import CommonMapAsync._
  // #mapasync-strict-order

  events
    .mapAsync(1) { in =>
      eventHandler(in)
    }
    .map { in =>
      println(s"`mapAsync` emitted event number: $in")
    }
    // #mapasync-strict-order
    .runWith(Sink.ignore)

}

object MapAsync extends App {
  import CommonMapAsync._
  // #mapasync-concurrent

  events
    .mapAsync(3) { in =>
      eventHandler(in)
    }
    .map { in =>
      println(s"`mapAsync` emitted event number: $in")
    }
    // #mapasync-concurrent
    .runWith(Sink.ignore)

}

object MapAsyncUnordered extends App {
  import CommonMapAsync._
  // #mapasyncunordered

  events
    .mapAsyncUnordered(3) { in =>
      eventHandler(in)
    }
    .map { in =>
      println(s"`mapAsyncUnordered` emitted event number: $in")
    }
    // #mapasyncunordered
    .runWith(Sink.ignore)

}
