package docs.stream

import akka.stream.{ ClosedShape, OverflowStrategy, ActorMaterializer }
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class GraphCyclesSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  "Cycle demonstration" must {
    val source = Source(() => Iterator.from(0))

    "include a deadlocked cycle" in {

      // format: OFF
      //#deadlocked
      // WARNING! The graph below deadlocks!
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[Int](2))
        val log = b.add(Flow[Int].map { s => println(s); s } )

        b.add(source) ~> merge ~> log ~> bcast ~> b.add(Sink.ignore)
                         merge     <~    bcast
        ClosedShape
      })
      //#deadlocked
      // format: ON
    }

    "include an unfair cycle" in {
      // format: OFF
      //#unfair
      // WARNING! The graph below stops consuming from "source" after a few steps
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(MergePreferred[Int](1))
        val bcast = b.add(Broadcast[Int](2))
        val log = b.add(Flow[Int].map { s => println(s); s })

        b.add(source) ~> merge ~> log ~> bcast ~> b.add(Sink.ignore)
                  merge.preferred   <~   bcast
        ClosedShape
      })
      //#unfair
      // format: ON
    }

    "include a dropping cycle" in {
      // format: OFF
      //#dropping
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[Int](2))
        val log = b.add(Flow[Int].map { s => println(s); s })
        val safetyValve = b.add(Flow[Int].buffer(10, OverflowStrategy.dropHead))

        b.add(source) ~> merge ~> log         ~> bcast ~> b.add(Sink.ignore)
                         merge <~ safetyValve <~ bcast
        ClosedShape
      })
      //#dropping
      // format: ON
    }

    "include a dead zipping cycle" in {
      // format: OFF
      //#zipping-dead
      // WARNING! The graph below never processes any elements
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._

        val zip = b.add(ZipWith[Int, Int, Int]((left, right) => right))
        val bcast = b.add(Broadcast[Int](2))

        b.add(source) ~> zip.in0
        zip.out.map { s => println(s); s } ~> bcast ~> b.add(Sink.ignore)
        zip.in1             <~                bcast
        ClosedShape
      })
      //#zipping-dead
      // format: ON
    }

    "include a live zipping cycle" in {
      // format: OFF
      //#zipping-live
      RunnableGraph.fromGraph(FlowGraph.create() { implicit b =>
        import FlowGraph.Implicits._

        val zip = b.add(ZipWith((left: Int, right: Int) => left))
        val bcast = b.add(Broadcast[Int](2))
        val concat = b.add(Concat[Int]())
        val start = b.add(Source.single(0))

        b.add(source) ~> zip.in0
        zip.out.map { s => println(s); s } ~> bcast ~> b.add(Sink.ignore)
        zip.in1 <~ concat <~ start
                   concat         <~          bcast
        ClosedShape
      })
      //#zipping-live
      // format: ON
    }

  }

}
