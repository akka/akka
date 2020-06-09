/*
 * Copyright (C) 2015-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.scaladsl._
import akka.stream.testkit.StreamSpec

class FusingSpec extends StreamSpec {

  def actorRunningStage = {
    GraphInterpreter.currentInterpreter.context
  }

  val snitchFlow = Flow[Int].map(x => { testActor ! actorRunningStage; x }).async

  "SubFusingActorMaterializer" must {

    "work with asynchronous boundaries in the subflows" in {
      val async = Flow[Int].map(_ * 2).async
      Source(0 to 9)
        .map(_ * 10)
        .flatMapMerge(5, i => Source(i to (i + 9)).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 198 by 2)
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (manual)" in {
      val async = Flow[Int].map(x => { testActor ! actorRunningStage; x }).async
      Source(0 to 9)
        .via(snitchFlow.async)
        .flatMapMerge(5, i => Source.single(i).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      refs.toSet should have size (11) // main flow + 10 subflows
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (operator)" in {
      Source(0 to 9)
        .via(snitchFlow)
        .flatMapMerge(5, i => Source.single(i).via(snitchFlow.async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      refs.toSet should have size (11) // main flow + 10 subflows
    }

    "use one actor per grouped substream when there is an async boundary around the flow (manual)" in {
      val in = 0 to 9
      Source(in)
        .via(snitchFlow)
        .groupBy(in.size, identity)
        .via(snitchFlow.async)
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue
        .sorted should ===(in)
      val refs = receiveN(in.size + in.size) // each element through the first map, then the second map

      refs.toSet should have size (in.size + 1) // outer/main actor + 1 actor per subflow
    }

    "use one actor per grouped substream when there is an async boundary around the flow (operator)" in {
      val in = 0 to 9
      Source(in)
        .via(snitchFlow)
        .groupBy(in.size, identity)
        .via(snitchFlow)
        .async
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue
        .sorted should ===(in)
      val refs = receiveN(in.size + in.size) // each element through the first map, then the second map
      refs.toSet should have size (in.size + 1) // outer/main actor + 1 actor per subflow
    }

    "propagate downstream errors through async boundary" in {
      val slowInitSrc = Source
        .unfoldResource( () => {Thread.sleep(5000)},
          (_ : Unit) => Some(1),
          (_ : Unit) => ()
        )
        .watchTermination()(Keep.right)
        .async  //commenting this out, makes the test pass
      val downstream = Flow[Int]
        .prepend(Source single 1)
        .flatMapPrefix(0){
          case Nil => sys error "I hate mondays"
        }
        .watchTermination()(Keep.right)
        .to(Sink.ignore)

      val g = slowInitSrc
        .toMat(downstream)(Keep.both)

      val (f1, f2) = g.run()
      f2.failed.futureValue should have message("I hate mondays")
      Thread.sleep(5000)
      f1.failed.futureValue should have message("I hate mondays")
    }

    "propagate 'parallel' errors through async boundary via a common downstream" in {
      val slowInitSrc = Source
        .unfoldResource( () => {Thread.sleep(5000)},
          (_ : Unit) => Some(1),
          (_ : Unit) => ()
        )
        .watchTermination()(Keep.right)
        .async  //commenting this out, makes the test pass

      val failingSrc = Source
        .failed(new RuntimeException("I hate mondays"))
        .watchTermination()(Keep.right)

      val g = slowInitSrc
        .zipMat(failingSrc)(Keep.both)
        .to(Sink.ignore)

      val (f1, f2) = g.run()
      f2.failed.futureValue should have message("I hate mondays")
      Thread.sleep(5000)
      f1.failed.futureValue should have message("I hate mondays")
    }

  }

}
