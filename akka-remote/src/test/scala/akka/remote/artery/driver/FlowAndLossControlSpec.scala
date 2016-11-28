package akka.remote.artery.driver

import java.util.concurrent.ThreadLocalRandom

import akka.NotUsed
import akka.io.DirectByteBufferPool
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import akka.util.ByteString

class FlowAndLossControlSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  val DropRate = 0.1

  val droppyFlow = Flow[Frame].mapConcat { frame ⇒
    if (ThreadLocalRandom.current().nextDouble() < DropRate) {
      frame.release()
      Nil
    } else List(frame)
  }

  val pool = new DirectByteBufferPool(FrameBuffer.BufferSize, 2)
  val frameBuffer = new FrameBuffer(pool)

  "Flow and loss control" must {

    // FIXME: Stage completion
    // FXIME: Longer chains

    "work looped back to self" in {
      val Count = 1000
      val testData = List.tabulate(Count)(i ⇒ ByteString.fromArray(Array.fill[Byte](i + 1)(i.toByte)))

      val flow: Flow[ByteString, ByteString, NotUsed] =
        FlowAndLossControl(frameBuffer) join Flow[Frame]

      Source(testData).via(flow).take(Count).runWith(Sink.seq).futureValue should ===(testData)
    }

    "work looped through a counter-pair" in {
      val Count = 1000
      val testData = List.tabulate(Count)(i ⇒ ByteString.fromArray(Array.fill[Byte](i + 1)(i.toByte)))

      val flow: Flow[ByteString, ByteString, NotUsed] =
        FlowAndLossControl(frameBuffer) atop FlowAndLossControl(frameBuffer).reversed join Flow[ByteString]

      Source(testData).via(flow).take(Count).runWith(Sink.seq).futureValue should ===(testData)
    }

    "work on a lossy channel, looped back to self" in {
      val Count = 1000
      val testData = List.tabulate(Count)(i ⇒ ByteString.fromArray(Array.fill[Byte](i + 1)(i.toByte)))

      val flow: Flow[ByteString, ByteString, NotUsed] =
        FlowAndLossControl(frameBuffer) join droppyFlow

      Source(testData).via(flow).take(Count).runWith(Sink.seq).futureValue should ===(testData)
    }

    //    "work on a lossy channel looped through a counter-pair" in {
    //      val Count = 100
    //      val testData = List.tabulate(Count)(i ⇒ ByteString.fromInts(i))
    //
    //      val lossyBidi = BidiFlow.fromFlows(droppyFlow, droppyFlow)
    //
    //      val flow: Flow[ByteString, ByteString, NotUsed] =
    //        FlowAndLossControl(frameBuffer) atop lossyBidi atop FlowAndLossControl(frameBuffer).reversed join Flow[ByteString]
    //
    //      Source(testData).via(flow).take(Count).map { bs ⇒
    //        println("GOT " + bs(0))
    //        bs
    //      }.runWith(Sink.seq).futureValue should ===(testData)
    //    }

  }

}
