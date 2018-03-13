/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.stream.scaladsl._
import akka.stream.testkit.StreamSpec
import akka.stream.impl.fusing.GraphInterpreter
import akka.event.BusLogging

class FusingSpec extends StreamSpec {

  final val Debug = false
  implicit val materializer = ActorMaterializer()

  def graph(async: Boolean) =
    Source.unfold(1)(x ⇒ Some(x → x)).filter(_ % 2 == 1)
      .alsoTo(Flow[Int].fold(0)(_ + _).to(Sink.head.named("otherSink")).addAttributes(if (async) Attributes.asyncBoundary else Attributes.none))
      .via(Flow[Int].fold(1)(_ + _).named("mainSink"))

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
      def ref = {
        val bus = GraphInterpreter.currentInterpreter.log.asInstanceOf[BusLogging]
        bus.logSource
      }
      val async = Flow[Int].map(x ⇒ { testActor ! ref; x }).async
      Source(0 to 9)
        .map(x ⇒ { testActor ! ref; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      withClue(s"refs=\n${refs.mkString("\n")}") {
        refs.toSet.size should ===(11) // main flow + 10 subflows
      }
    }

    "use multiple actors when there are asynchronous boundaries in the subflows (combinator)" in {
      def ref = {
        val bus = GraphInterpreter.currentInterpreter.log.asInstanceOf[BusLogging]
        bus.logSource
      }
      val flow = Flow[Int].map(x ⇒ { testActor ! ref; x })
      Source(0 to 9)
        .map(x ⇒ { testActor ! ref; x })
        .flatMapMerge(5, i ⇒ Source.single(i).via(flow.async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 9)
      val refs = receiveN(20)
      withClue(s"refs=\n${refs.mkString("\n")}") {
        refs.toSet.size should ===(11) // main flow + 10 subflows
      }
    }

  }

}
