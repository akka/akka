package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

class StreamBuffersRateSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "Demonstrate pipelining" in {
    def println(s: Any) = ()
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
    val materializer = ActorMaterializer(
      ActorMaterializerSettings(system)
        .withInputBuffer(
          initialSize = 64,
          maxSize = 64))
    //#materializer-buffer

    //#section-buffer
    val section = Flow[Int].map(_ * 2)
      .withAttributes(Attributes.inputBuffer(initial = 1, max = 1))
    val flow = section.via(Flow[Int].map(_ / 2)) // the buffer size of this map is the default
    //#section-buffer
  }

  "buffering abstraction leak" in {
    //#buffering-abstraction-leak
    import scala.concurrent.duration._
    case class Tick()

    RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val zipper = b.add(ZipWith[Tick, Int, Int]((tick, count) => count))

      Source.tick(initialDelay = 3.second, interval = 3.second, Tick()) ~> zipper.in0

      Source.tick(initialDelay = 1.second, interval = 1.second, "message!")
        .conflate(seed = (_) => 1)((count, _) => count + 1) ~> zipper.in1

      zipper.out ~> Sink.foreach(println)
      ClosedShape
    })
    //#buffering-abstraction-leak
  }

  "explcit buffers" in {
    trait Job
    def inboundJobsConnector(): Source[Job, Unit] = Source.empty
    //#explicit-buffers-backpressure
    // Getting a stream of jobs from an imaginary external system as a Source
    val jobs: Source[Job, Unit] = inboundJobsConnector()
    jobs.buffer(1000, OverflowStrategy.backpressure)
    //#explicit-buffers-backpressure

    //#explicit-buffers-droptail
    jobs.buffer(1000, OverflowStrategy.dropTail)
    //#explicit-buffers-droptail

    //#explicit-buffers-dropnew
    jobs.buffer(1000, OverflowStrategy.dropNew)
    //#explicit-buffers-dropnew

    //#explicit-buffers-drophead
    jobs.buffer(1000, OverflowStrategy.dropHead)
    //#explicit-buffers-drophead

    //#explicit-buffers-dropbuffer
    jobs.buffer(1000, OverflowStrategy.dropBuffer)
    //#explicit-buffers-dropbuffer

    //#explicit-buffers-fail
    jobs.buffer(1000, OverflowStrategy.fail)
    //#explicit-buffers-fail

  }

}
