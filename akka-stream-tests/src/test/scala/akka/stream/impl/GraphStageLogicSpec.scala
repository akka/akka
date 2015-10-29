/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.testkit.scaladsl.TestSink

class GraphStageLogicSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  "A GraphStageLogic" must {

    "emit all things before completing" in {

      Source.empty[Int].via(new GraphStage[FlowShape[Int, Int]] {
        private val in = Inlet[Int]("in")
        private val out = Outlet[Int]("out")
        override val shape = FlowShape(in, out)
        override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
          setHandler(in, eagerTerminateInput)
          setHandler(out, eagerTerminateOutput)
          override def preStart(): Unit = {
            emit(out, 1, () ⇒ emit(out, 2))
            emit(out, 3, () ⇒ emit(out, 4))
          }
        }
      }.named("testStage")).runWith(TestSink.probe)
        .request(5)
        .expectNext(1, 2, 3, 4)
        .expectComplete()

    }

  }

}