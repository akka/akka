package docs.stream

import akka.stream.{ OverflowStrategy, FlowMaterializer }
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class GraphCyclesSpec extends AkkaSpec {

  implicit val mat = FlowMaterializer()

  "Cycle demonstration" must {
    val source = Source(() => Iterator.from(0))

    "include a deadlocked cycle" in {

      //#deadlocked
      // WARNING! The graph below deadlocks!
      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        b.allowCycles()

        val merge = Merge[Int]
        val bcast = Broadcast[Int]

        source ~> merge ~> Flow[Int].map { (s) => println(s); s } ~> bcast ~> Sink.ignore
        bcast ~> merge
      }
      //#deadlocked

    }

    "include an unfair cycle" in {
      //#unfair
      // WARNING! The graph below stops consuming from "source" after a few steps
      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        b.allowCycles()

        val merge = MergePreferred[Int]
        val bcast = Broadcast[Int]

        source ~> merge ~> Flow[Int].map { (s) => println(s); s } ~> bcast ~> Sink.ignore
        bcast ~> merge.preferred
      }
      //#unfair

    }

    "include a dropping cycle" in {
      //#dropping
      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        b.allowCycles()

        val merge = Merge[Int]
        val bcast = Broadcast[Int]

        source ~> merge ~> Flow[Int].map { (s) => println(s); s } ~> bcast ~> Sink.ignore
        bcast ~> Flow[Int].buffer(10, OverflowStrategy.dropHead) ~> merge
      }
      //#dropping

    }

    "include a dead zipping cycle" in {
      //#zipping-dead
      // WARNING! The graph below never processes any elements
      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        b.allowCycles()

        val zip = ZipWith[Int, Int, Int]((left, right) => right)
        val bcast = Broadcast[Int]

        source ~> zip.left ~> Flow[Int].map { (s) => println(s); s } ~> bcast ~> Sink.ignore
        bcast ~> zip.right
      }
      //#zipping-dead

    }

    "include a live zipping cycle" in {
      //#zipping-live
      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        b.allowCycles()

        val zip = ZipWith[Int, Int, Int]((left, right) => left)
        val bcast = Broadcast[Int]
        val concat = Concat[Int]

        source ~> zip.left ~> Flow[Int].map { (s) => println(s); s } ~> bcast ~> Sink.ignore
        bcast ~> concat.second ~> zip.right
        Source.single(0) ~> concat.first

      }
      //#zipping-live

    }

  }

}
