package docs.stream

import akka.stream.{ OverflowStrategy, MaterializerSettings, FlowMaterializer }
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class StreamBuffersRateSpec extends AkkaSpec {
  implicit val mat = FlowMaterializer()

  "Demonstrate pipelining" in {
    //#pipelining
    Source(1 to 3)
      .map { i => println(s"A: $i"); i }
      .map { i => println(s"B: $i"); i }
      .map { i => println(s"C: $i"); i }
      .runWith(Sink.ignore)
    //#pipelining
  }

  "Demonstrate buffer sizes" in {
    //#materializer-buffer
    val materializer = FlowMaterializer(
      MaterializerSettings(system)
        .withInputBuffer(
          initialSize = 64,
          maxSize = 64))
    //#materializer-buffer

    //#section-buffer
    val flow =
      Flow[Int]
        .section(OperationAttributes.inputBuffer(initial = 1, max = 1)) { sectionFlow =>
          // the buffer size of this map is 1
          sectionFlow.map(_ * 2)
        }
        .map(_ / 2) // the buffer size of this map is the default
    //#section-buffer
  }

  "buffering abstraction leak" in {
    //#buffering-abstraction-leak
    import scala.concurrent.duration._
    case class Tick()

    FlowGraph { implicit b =>
      import FlowGraphImplicits._

      val zipper = ZipWith[Tick, Int, Int]((tick, count) => count)

      Source(initialDelay = 1.second, interval = 1.second, () => "message!")
        .conflate(seed = (_) => 1)((count, _) => count + 1) ~> zipper.right

      Source(initialDelay = 3.second, interval = 3.second, () => Tick()) ~> zipper.left

      zipper.out ~> Sink.foreach(println)
    }
    //#buffering-abstraction-leak
  }

}
