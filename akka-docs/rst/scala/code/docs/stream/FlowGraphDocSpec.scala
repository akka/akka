/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec

import scala.collection.immutable
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.stream.Attributes

class FlowGraphDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val materializer = ActorMaterializer()

  "build simple graph" in {
    //format: OFF
    //#simple-flow-graph
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val in = Source(1 to 10)
      val out = Sink.ignore

      val bcast = builder.add(Broadcast[Int](2))
      val merge = builder.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
      bcast ~> f4 ~> merge
      ClosedShape
    })
    //#simple-flow-graph
    //format: ON

    //#simple-graph-run
    g.run()
    //#simple-graph-run
  }

  "flow connection errors" in {
    intercept[IllegalArgumentException] {
      //#simple-graph
      RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._
        val source1 = Source(1 to 10)
        val source2 = Source(1 to 10)

        val zip = builder.add(Zip[Int, Int]())

        source1 ~> zip.in0
        source2 ~> zip.in1
        // unconnected zip.out (!) => "must have at least 1 outgoing edge"
        ClosedShape
      })
      //#simple-graph
    }.getMessage should include("ZipWith2.out")
  }

  "reusing a flow in a graph" in {
    //#flow-graph-reusing-a-flow

    val topHeadSink = Sink.head[Int]
    val bottomHeadSink = Sink.head[Int]
    val sharedDoubler = Flow[Int].map(_ * 2)

    //#flow-graph-reusing-a-flow

    // format: OFF
    val g =
    //#flow-graph-reusing-a-flow
    RunnableGraph.fromGraph(GraphDSL.create(topHeadSink, bottomHeadSink)((_, _)) { implicit builder =>
      (topHS, bottomHS) =>
      import GraphDSL.Implicits._
      val broadcast = builder.add(Broadcast[Int](2))
      Source.single(1) ~> broadcast.in

      broadcast.out(0) ~> sharedDoubler ~> topHS.in
      broadcast.out(1) ~> sharedDoubler ~> bottomHS.in
      ClosedShape
    })
    //#flow-graph-reusing-a-flow
    // format: ON
    val (topFuture, bottomFuture) = g.run()
    Await.result(topFuture, 300.millis) shouldEqual 2
    Await.result(bottomFuture, 300.millis) shouldEqual 2
  }

  "building a reusable component" in {

    //#flow-graph-components-shape
    // A shape represents the input and output ports of a reusable
    // processing module
    case class PriorityWorkerPoolShape[In, Out](
      jobsIn: Inlet[In],
      priorityJobsIn: Inlet[In],
      resultsOut: Outlet[Out]) extends Shape {

      // It is important to provide the list of all input and output
      // ports with a stable order. Duplicates are not allowed.
      override val inlets: immutable.Seq[Inlet[_]] =
        jobsIn :: priorityJobsIn :: Nil
      override val outlets: immutable.Seq[Outlet[_]] =
        resultsOut :: Nil

      // A Shape must be able to create a copy of itself. Basically
      // it means a new instance with copies of the ports
      override def deepCopy() = PriorityWorkerPoolShape(
        jobsIn.carbonCopy(),
        priorityJobsIn.carbonCopy(),
        resultsOut.carbonCopy())

      // A Shape must also be able to create itself from existing ports
      override def copyFromPorts(
        inlets: immutable.Seq[Inlet[_]],
        outlets: immutable.Seq[Outlet[_]]) = {
        assert(inlets.size == this.inlets.size)
        assert(outlets.size == this.outlets.size)
        // This is why order matters when overriding inlets and outlets.
        PriorityWorkerPoolShape[In, Out](inlets(0).as[In], inlets(1).as[In], outlets(0).as[Out])
      }
    }
    //#flow-graph-components-shape

    //#flow-graph-components-create
    object PriorityWorkerPool {
      def apply[In, Out](
        worker: Flow[In, Out, Any],
        workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = {

        GraphDSL.create() { implicit b ⇒
          import GraphDSL.Implicits._

          val priorityMerge = b.add(MergePreferred[In](1))
          val balance = b.add(Balance[In](workerCount))
          val resultsMerge = b.add(Merge[Out](workerCount))

          // After merging priority and ordinary jobs, we feed them to the balancer
          priorityMerge ~> balance

          // Wire up each of the outputs of the balancer to a worker flow
          // then merge them back
          for (i <- 0 until workerCount)
            balance.out(i) ~> worker ~> resultsMerge.in(i)

          // We now expose the input ports of the priorityMerge and the output
          // of the resultsMerge as our PriorityWorkerPool ports
          // -- all neatly wrapped in our domain specific Shape
          PriorityWorkerPoolShape(
            jobsIn = priorityMerge.in(0),
            priorityJobsIn = priorityMerge.preferred,
            resultsOut = resultsMerge.out)
        }

      }

    }
    //#flow-graph-components-create

    def println(s: Any): Unit = ()

    //#flow-graph-components-use
    val worker1 = Flow[String].map("step 1 " + _)
    val worker2 = Flow[String].map("step 2 " + _)

    RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
      val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

      Source(1 to 100).map("job: " + _) ~> priorityPool1.jobsIn
      Source(1 to 100).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

      priorityPool1.resultsOut ~> priorityPool2.jobsIn
      Source(1 to 100).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

      priorityPool2.resultsOut ~> Sink.foreach(println)
      ClosedShape
    }).run()
    //#flow-graph-components-use

    //#flow-graph-components-shape2
    import FanInShape.Name
    import FanInShape.Init

    class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool"))
      extends FanInShape[Out](_init) {
      protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

      val jobsIn = newInlet[In]("jobsIn")
      val priorityJobsIn = newInlet[In]("priorityJobsIn")
      // Outlet[Out] with name "out" is automatically created
    }
    //#flow-graph-components-shape2

  }

  "access to materialized value" in {
    //#flow-graph-matvalue
    import GraphDSL.Implicits._
    val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) {
      implicit builder ⇒
        fold ⇒
          FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
    })
    //#flow-graph-matvalue

    Await.result(Source(1 to 10).via(foldFlow).runWith(Sink.head), 3.seconds) should ===(55)

    //#flow-graph-matvalue-cycle
    import GraphDSL.Implicits._
    // This cannot produce any value:
    val cyclicFold: Source[Int, Future[Int]] = Source.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) {
      implicit builder =>
        fold =>
          // - Fold cannot complete until its upstream mapAsync completes
          // - mapAsync cannot complete until the materialized Future produced by
          //   fold completes
          // As a result this Source will never emit anything, and its materialited
          // Future will never complete
          builder.materializedValue.mapAsync(4)(identity) ~> fold
          SourceShape(builder.materializedValue.mapAsync(4)(identity).outlet)
    })
    //#flow-graph-matvalue-cycle
  }

}
