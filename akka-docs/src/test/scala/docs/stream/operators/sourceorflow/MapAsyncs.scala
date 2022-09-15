/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

/**
 *
 */
object CommonMapAsync {
  sealed trait Event {
    def sequenceNumber: Int
  }

  object Event {
    def apply(sequenceNumber: Int): PlainEvent = PlainEvent(sequenceNumber)
  }

  case class PlainEvent(sequenceNumber: Int) extends Event
  case class EntityEvent(entityId: Int, sequenceNumber: Int) extends Event

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

object MapAsyncPartitioned extends App {
  import CommonMapAsync._

  // #mapAsyncPartitioned
  val eventsForEntities: Source[EntityEvent, NotUsed] = // ...
    // #mapAsyncPartitioned
    Source
      .fromIterator { () =>
        Iterator.from(1)
      }
      .throttle(1, 1.millis)
      .statefulMapConcat { () =>
        var countsPerEntity = immutable.Map.empty[Int, Int]

        { n =>
          val entityId =
            if (Random.nextBoolean || countsPerEntity.isEmpty) Random.nextInt(n)
            else {
              val m = Random.nextInt(countsPerEntity.size) + 1
              countsPerEntity.keys.iterator.take(m).drop(m - 1).next()
            }
          val seqNr = countsPerEntity.getOrElse(entityId, 0) + 1
          countsPerEntity = countsPerEntity + (entityId -> seqNr)
          List(EntityEvent(entityId, seqNr))
        }
      }
      .take(1000)

  // #mapAsyncPartitioned
  val partitioner: EntityEvent => Int = { event =>
    val partition = event.entityId
    println(s"Assigned event $event to partition $partition")
    partition
  }

  eventsForEntities
    .statefulMapConcat { () =>
      var offset = 0

      { event =>
        println(s"Received event $event at offset $offset from message broker")
        offset += 1
        List(event)
      }
    }
    .mapAsyncPartitioned(10, 1)(partitioner) { (event, partition) =>
      println(s"Processing event $event from partition $partition")

      val fut = Future.successful(s"$partition-${event.sequenceNumber}")
      val result =
        if (Random.nextBoolean) {
          val delay = Random.nextInt(1000).millis
          akka.pattern.after(delay)(fut)
        } else {
          fut
        }

      result.map { x =>
        println(s"Completed processing $x")
        x
      }
    }
    .map { in =>
      println(s"`mapAsyncPartitioned` emitted $in")
    }
    // #mapAsyncPartitioned
    .runWith(Sink.ignore)
    .flatMap { _ =>
      // give the printlns a chance to print
      akka.pattern.after(1.second)(Future.unit)
    }
    .foreach { _ =>
      System.out.flush()
      sys.terminate()
    }
}
