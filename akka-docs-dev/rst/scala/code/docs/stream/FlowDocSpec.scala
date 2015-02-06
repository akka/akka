/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.actor.Cancellable
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

import concurrent.Future

class FlowDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  //#imports
  import akka.stream.ActorFlowMaterializer
  //#imports

  implicit val mat = ActorFlowMaterializer()

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

  "materializedMap is unique" in {
    //#stream-reuse
    // connect the Source to the Sink, obtaining a RunnableFlow
    val sink = Sink.fold[Int, Int](0)(_ + _)
    val runnable: RunnableFlow = Source(1 to 10).to(sink)

    // get the materialized value of the FoldSink
    val sum1: Future[Int] = runnable.run().get(sink)
    val sum2: Future[Int] = runnable.run().get(sink)

    // sum1 and sum2 are different Futures!
    //#stream-reuse
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
    // retain the materialized map, in order to retrieve the timer's Cancellable
    val materialized = timerMap.to(Sink.ignore).run()
    val timerCancellable = materialized.get(timer)
    timerCancellable.cancel()
    //#compound-source-is-not-keyed-run
  }

  "creating sources, sinks" in {
    //#source-sink
    // Create a source from an Iterable
    Source(List(1, 2, 3))

    // Create a source form a Future
    Source(Future.successful("Hello Streams!"))

    // Create a source from a single element
    Source.single("only one element")

    // an empty source
    Source.empty

    // Sink that folds over the stream and returns a Future
    // of the final result in the MaterializedMap
    Sink.fold[Int, Int](0)(_ + _)

    // Sink that returns a Future in the MaterializedMap,
    // containing the first element of the stream
    Sink.head

    // A Sink that consumes a stream without doing anything with the elements
    Sink.ignore

    // A Sink that executes a side-effecting call for every element of the stream
    Sink.foreach[String](println(_))
    //#source-sink
  }

  "various ways of connecting source, sink, flow" in {
    //#flow-connecting
    // Explicitly creating and wiring up a Source, Sink and Flow
    Source(1 to 6).via(Flow[Int].map(_ * 2)).to(Sink.foreach(println(_)))

    // Starting from a Source
    val source = Source(1 to 6).map(_ * 2)
    source.to(Sink.foreach(println(_)))

    // Starting from a Sink
    val sink: Sink[Int] = Flow[Int].map(_ * 2).to(Sink.foreach(println(_)))
    Source(1 to 6).to(sink)

    //#flow-connecting
  }
}
