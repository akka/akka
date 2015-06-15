package docs.stream

import akka.stream.{ OverflowStrategy, ActorMaterializer }
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
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[Int](2))

        source ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
                  merge                    <~                      bcast
      }
      //#deadlocked
      // format: ON
    }

    "include an unfair cycle" in {
      // format: OFF
      //#unfair
      // WARNING! The graph below stops consuming from "source" after a few steps
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(MergePreferred[Int](1))
        val bcast = b.add(Broadcast[Int](2))

        source ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
                  merge.preferred              <~                  bcast
      }
      //#unfair
      // format: ON
    }

    "include a dropping cycle" in {
      // format: OFF
      //#dropping
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val merge = b.add(Merge[Int](2))
        val bcast = b.add(Broadcast[Int](2))

        source ~> merge ~> Flow[Int].map { s => println(s); s } ~> bcast ~> Sink.ignore
            merge <~ Flow[Int].buffer(10, OverflowStrategy.dropHead) <~ bcast
      }
      //#dropping
      // format: ON
    }

    "include a dead zipping cycle" in {
      // format: OFF
      //#zipping-dead
      // WARNING! The graph below never processes any elements
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val zip = b.add(ZipWith[Int, Int, Int]((left, right) => right))
        val bcast = b.add(Broadcast[Int](2))

        source ~> zip.in0
        zip.out.map { s => println(s); s } ~> bcast ~> Sink.ignore
        zip.in1             <~                bcast
      }
      //#zipping-dead
      // format: ON
    }

    "include a live zipping cycle" in {
      // format: OFF
      //#zipping-live
      FlowGraph.closed() { implicit b =>
        import FlowGraph.Implicits._

        val zip = b.add(ZipWith((left: Int, right: Int) => left))
        val bcast = b.add(Broadcast[Int](2))
        val concat = b.add(Concat[Int]())
        val start = Source.single(0)

        source ~> zip.in0
        zip.out.map { s => println(s); s } ~> bcast ~> Sink.ignore
        zip.in1 <~ concat <~ start
                   concat         <~          bcast
      }
      //#zipping-live
      // format: ON
    }

  }

}
