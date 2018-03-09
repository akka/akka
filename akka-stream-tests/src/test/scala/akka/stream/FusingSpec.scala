/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.scaladsl._
import akka.stream.testkit.StreamSpec

class FusingSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()

  def actorRunningStage = {
    GraphInterpreter.currentInterpreter.context
  }

  "SubFusingActorMaterializer" must {

    "work with asynchronous boundaries in the subflows" in {
      val async = Flow[Int].map(_ * 2).async
      Source(0 to 9)
        .map(_ * 10)
        .flatMapMerge(5, i ⇒ Source(i to (i + 9)).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 198 by 2)
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (manual)" in {
      val async = Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x }).async
      Source(0 to 9)
        .map(x ⇒ { testActor ! actorRunningStage; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      refs.toSet should have size (11) // main flow + 10 subflows
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (combinator)" in {
      val flow = Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x })
      Source(0 to 9)
        .map(x ⇒ { testActor ! actorRunningStage; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(flow.async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      refs.toSet should have size (11) // main flow + 10 subflows
    }

    "use one actor per grouped substream when there is an async boundary around the flow (manual)" in {
      val flow = Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x }).async
      val in = 0 to 9
      Source(in)
        .via(Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x }))
        .groupBy(in.size, identity)
        .via(flow)
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue.sorted should ===(in)
      val refs = receiveN(in.size + in.size) // each element through the first map, then the second map

      refs.toSet should have size (in.size + 1) // outer/main actor + 1 actor per subflow
    }

    "use one actor per grouped substream when there is an async boundary around the flow (combinator)" in {
      val flow = Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x })
      val in = 0 to 9
      Source(in)
        .via(Flow[Int].map(x ⇒ { testActor ! actorRunningStage; x }))
        .groupBy(in.size, identity)
        .via(flow)
        .async
        .mergeSubstreams
        .runWith(Sink.seq)
        .futureValue.sorted should ===(in)
      val refs = receiveN(in.size + in.size) // each element through the first map, then the second map
      refs.toSet should have size (in.size + 1) // outer/main actor + 1 actor per subflow
    }

  }

}
