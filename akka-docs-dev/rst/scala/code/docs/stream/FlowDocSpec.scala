/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.actor.Cancellable
import akka.stream.scaladsl.MaterializedMap
import akka.stream.scaladsl.RunnableFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec

import concurrent.Future

class FlowDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  //#imports
  import akka.stream.FlowMaterializer
  //#imports

  implicit val mat = FlowMaterializer()

  "source is immutable" in {
    //#source-immutable
    val source = Source(1 to 10)
    source.map(_ => 0) // has no effect on source, since it's immutable
    source.runWith(Sink.fold(0)(_ + _)) // 55

    val zeroes = source.map(_ => 0) // returns new Source[Int], with `map()` appended
    zeroes.runWith(Sink.fold(0)(_ + _)) // 0
    //#source-immutable
  }

  "materialization in steps" in {
    //#materialization-in-steps
    val source = Source(1 to 10)
    val sink = Sink.fold[Int, Int](0)(_ + _)

    // connect the Source to the Sink, obtaining a RunnableFlow
    val runnable: RunnableFlow = source.to(sink)

    // materialize the flow
    val materialized: MaterializedMap = runnable.run()

    // get the materialized value of the FoldSink
    val sum: Future[Int] = materialized.get(sink)

    //#materialization-in-steps
  }

  "materialization runWith" in {
    //#materialization-runWith
    val source = Source(1 to 10)
    val sink = Sink.fold[Int, Int](0)(_ + _)

    // materialize the flow, getting the Sinks materialized value
    val sum: Future[Int] = source.runWith(sink)
    //#materialization-runWith
  }

  "compound source cannot be used as key" in {
    //#compound-source-is-not-keyed-runWith
    import scala.concurrent.duration._
    case object Tick

    val timer = Source(initialDelay = 1.second, interval = 1.seconds, tick = () => Tick)

    val timerCancel: Cancellable = Sink.ignore.runWith(timer)
    timerCancel.cancel()

    val timerMap = timer.map(tick => "tick")
    val _ = Sink.ignore.runWith(timerMap) // WRONG: returned type is not the timers Cancellable!
    //#compound-source-is-not-keyed-runWith

    //#compound-source-is-not-keyed-run
    // retain the materialized map, in order to retrieve the timers Cancellable
    val materialized = timerMap.to(Sink.ignore).run()
    val timerCancellable = materialized.get(timer)
    timerCancellable.cancel()
    //#compound-source-is-not-keyed-run
  }
}
