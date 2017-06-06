/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorMaterializer
import akka.stream.impl.fusing.SubSink.Command
import akka.stream.impl.fusing.{ SubSink, SubSource }
import akka.stream.stage.AsyncCallback
import akka.stream.testkit.{ TestSubscriber, AkkaSpec }
import akka.testkit.TestProbe

class GraphSubSourceSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "The SubSource graph stage" should {

    "not fail with cannot push twice exception if elements are pushed before initialized" in {
      // covering akka/akka#19623
      val callback = new AsyncCallback[SubSink.Command] {
        override def invoke(t: Command): Unit = {}
      }
      val subsource = new SubSource[String]("subsource", callback)

      val source = Source.fromGraph(subsource)
      val probe = TestSubscriber.manualProbe[String]()
      source.to(Sink.fromSubscriber(probe)).run()
      val sub = probe.expectSubscription()

      // push before there is demand
      subsource.pushSubstream("one")
      // this causes a cannot push twice exception before bug is fixed
      subsource.pushSubstream("two")

      sub.request(1)
      probe.expectNext("one")
      sub.request(1)
      probe.expectNext("two")

      sub.cancel()
    }

  }

}
