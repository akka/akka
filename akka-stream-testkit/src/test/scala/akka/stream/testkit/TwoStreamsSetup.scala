/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.stream._
import akka.stream.scaladsl._
import org.reactivestreams.Publisher

abstract class TwoStreamsSetup extends BaseTwoStreamsSetup {

  abstract class Fixture(b: GraphDSL.Builder[_]) {
    def left: Inlet[Int]
    def right: Inlet[Int]
    def out: Outlet[Outputs]
  }

  def fixture(b: GraphDSL.Builder[_]): Fixture

  override def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    RunnableGraph
      .fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val f = fixture(b)

        Source.fromPublisher(p1) ~> f.left
        Source.fromPublisher(p2) ~> f.right
        f.out ~> Sink.fromSubscriber(subscriber)
        ClosedShape
      })
      .run()

    subscriber
  }

}
