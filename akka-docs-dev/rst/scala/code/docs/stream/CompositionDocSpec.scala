package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class CompositionDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()

  "nonnested flow" in {
    //#non-nested-flow
    Source.single(0)
      .map(_ + 1)
      .filter(_ != 0)
      .map(_ - 2)
      .to(Sink.fold(0)(_ + _))

    // ... where is the nesting?
    //#non-nested-flow
  }

  "nested flow" in {
    //#nested-flow
    val nestedSource =
      Source.single(0) // An atomic source
        .map(_ + 1) // an atomic processing stage
        .named("nestedSource") // wraps up the current Source and gives it a name

    val nestedFlow =
      Flow[Int].filter(_ != 0) // an atomic processing stage
        .map(_ - 2) // another atomic processing stage
        .named("nestedFlow") // wraps up the Flow, and gives it a name

    val nestedSink =
      nestedFlow.to(Sink.fold(0)(_ + _)) // wire an atomic sink to the nestedFlow
        .named("nestedSink") // wrap it up

    // Create a RunnableGraph
    val runnableGraph = nestedSource.to(nestedSink)
    //#nested-flow
  }

  "reusing components" in {
    val nestedSource =
      Source.single(0) // An atomic source
        .map(_ + 1) // an atomic processing stage
        .named("nestedSource") // wraps up the current Source and gives it a name

    val nestedFlow =
      Flow[Int].filter(_ != 0) // an atomic processing stage
        .map(_ - 2) // another atomic processing stage
        .named("nestedFlow") // wraps up the Flow, and gives it a name

    val nestedSink =
      nestedFlow.to(Sink.fold(0)(_ + _)) // wire an atomic sink to the nestedFlow
        .named("nestedSink") // wrap it up

    //#reuse
    // Create a RunnableGraph from our components
    val runnableGraph = nestedSource.to(nestedSink)

    // Usage is uniform, no matter if modules are composite or atomic
    val runnableGraph2 = Source.single(0).to(Sink.fold(0)(_ + _))
    //#reuse
  }

  "complex graph" in {
    // format: OFF
    //#complex-graph
    import FlowGraph.Implicits._
    FlowGraph.closed() { implicit builder =>
      val A: Outlet[Int]                  = builder.add(Source.single(0))
      val B: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
      val C: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
      val D: FlowShape[Int, Int]          = builder.add(Flow[Int].map(_ + 1))
      val E: UniformFanOutShape[Int, Int] = builder.add(Balance[Int](2))
      val F: UniformFanInShape[Int, Int]  = builder.add(Merge[Int](2))
      val G: Inlet[Any]                   = builder.add(Sink.foreach(println))

                    C     <~      F
      A  ~>  B  ~>  C     ~>      F
             B  ~>  D  ~>  E  ~>  F
                           E  ~>  G
    }
    //#complex-graph

    //#complex-graph-alt
    import FlowGraph.Implicits._
    FlowGraph.closed() { implicit builder =>
      val B = builder.add(Broadcast[Int](2))
      val C = builder.add(Merge[Int](2))
      val E = builder.add(Balance[Int](2))
      val F = builder.add(Merge[Int](2))

      Source.single(0) ~> B.in; B.out(0) ~> C.in(1); C.out ~> F.in(0)
      C.in(0) <~ F.out

      B.out(1).map(_ + 1) ~> E.in; E.out(0) ~> F.in(1)
      E.out(1) ~> Sink.foreach(println)
    }
    //#complex-graph-alt
    // format: ON
  }

  "partial graph" in {
    // format: OFF
    //#partial-graph
    import FlowGraph.Implicits._
    val partial = FlowGraph.partial() { implicit builder =>
      val B = builder.add(Broadcast[Int](2))
      val C = builder.add(Merge[Int](2))
      val E = builder.add(Balance[Int](2))
      val F = builder.add(Merge[Int](2))

                                       C  <~  F
      B  ~>                            C  ~>  F
      B  ~>  Flow[Int].map(_ + 1)  ~>  E  ~>  F
      FlowShape(B.in, E.out(1))
    }.named("partial")
    //#partial-graph
    // format: ON

    //#partial-use
    Source.single(0).via(partial).to(Sink.ignore)
    //#partial-use

    // format: OFF
    //#partial-flow-dsl
    // Convert the partial graph of FlowShape to a Flow to get
    // access to the fluid DSL (for example to be able to call .filter())
    val flow = Flow.wrap(partial)

    // Simple way to create a graph backed Source
    val source = Source() { implicit builder =>
      val merge = builder.add(Merge[Int](2))
      Source.single(0)      ~> merge
      Source(List(2, 3, 4)) ~> merge

      // Exposing exactly one output port
      merge.out
    }

    // Building a Sink with a nested Flow, using the fluid DSL
    val sink = {
      val nestedFlow = Flow[Int].map(_ * 2).drop(10).named("nestedFlow")
      nestedFlow.to(Sink.head)
    }

    // Putting all together
    val closed = source.via(flow.filter(_ > 1)).to(sink)
    //#partial-flow-dsl
    // format: ON
  }

  "closed graph" in {
    //#embed-closed
    val closed1 = Source.single(0).to(Sink.foreach(println))
    val closed2 = FlowGraph.closed() { implicit builder =>
      val embeddedClosed: ClosedShape = builder.add(closed1)
    }
    //#embed-closed
  }
}
