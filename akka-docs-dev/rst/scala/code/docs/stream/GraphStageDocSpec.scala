/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.javadsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.stage.{ OutHandler, GraphStage, GraphStageLogic }
import akka.stream._

import akka.stream.testkit.AkkaSpec

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class GraphStageDocSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "Demonstrate creation of GraphStage boilerplate" in {
    //#boilerplate-example
    import akka.stream.SourceShape
    import akka.stream.stage.GraphStage

    class NumbersSource extends GraphStage[SourceShape[Int]] {
      // Define the (sole) output port of this stage
      val out: Outlet[Int] = Outlet("NumbersSource")
      // Define the shape of this stage, which is SourceShape with the port we defined above
      override val shape: SourceShape[Int] = SourceShape(out)

      // This is where the actual (possibly stateful) logic will live
      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = ???
    }
    //#boilerplate-example

  }

  "Demonstrate creation of GraphStage Source" in {
    //#custom-source-example
    import akka.stream.SourceShape
    import akka.stream.Graph
    import akka.stream.stage.GraphStage
    import akka.stream.stage.OutHandler

    class NumbersSource extends GraphStage[SourceShape[Int]] {
      val out: Outlet[Int] = Outlet("NumbersSource")
      override val shape: SourceShape[Int] = SourceShape(out)

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        new GraphStageLogic(shape) {
          // All state MUST be inside the GraphStageLogic,
          // never inside the enclosing GraphStage.
          // This state is safe to access and modify from all the
          // callbacks that are provided by GraphStageLogic and the
          // registered handlers.
          private var counter = 1

          setHandler(out, new OutHandler {
            override def onPull(): Unit = {
              push(out, counter)
              counter += 1
            }
          })
        }
    }
    //#custom-source-example

    //#simple-source-usage
    // A GraphStage is a proper Graph, just like what GraphDSL.create would return
    val sourceGraph: Graph[SourceShape[Int], Unit] = new NumbersSource

    // Create a Source from the Graph to access the DSL
    val mySource: Source[Int, Unit] = Source.fromGraph(new NumbersSource)

    // Returns 55
    val result1: Future[Int] = mySource.take(10).runFold(0)(_ + _)

    // The source is reusable. This returns 5050
    val result2: Future[Int] = mySource.take(100).runFold(0)(_ + _)
    //#simple-source-usage

    Await.result(result1, 3.seconds) should ===(55)
    Await.result(result2, 3.seconds) should ===(5050)
  }

}
