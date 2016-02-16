package docs.stream.cookbook

import java.security.MessageDigest

import akka.NotUsed
import akka.stream.{ Attributes, Outlet, Inlet, FlowShape }
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeDigest extends RecipeSpec {

  "Recipe for calculating digest" must {

    "work" in {

      val data = Source(List(
        ByteString("abcdbcdecdef"),
        ByteString("defgefghfghighijhijkijkljklmklmnlmnomnopnopq")))

      //#calculating-digest
      import akka.stream.stage._
      class DigestCalculator(algorithm: String) extends GraphStage[FlowShape[ByteString, ByteString]] {
        val in = Inlet[ByteString]("DigestCalculator.in")
        val out = Outlet[ByteString]("DigestCalculator.out")
        override val shape = FlowShape.of(in, out)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          val digest = MessageDigest.getInstance(algorithm)

          setHandler(out, new OutHandler {
            override def onPull(): Trigger = {
              pull(in)
            }
          })

          setHandler(in, new InHandler {
            override def onPush(): Trigger = {
              val chunk = grab(in)
              digest.update(chunk.toArray)
              pull(in)
            }

            override def onUpstreamFinish(): Unit = {
              emit(out, ByteString(digest.digest()))
              completeStage()
            }
          })

        }
      }
      val digest: Source[ByteString, NotUsed] = data.via(new DigestCalculator("SHA-256"))
      //#calculating-digest

      Await.result(digest.runWith(Sink.head), 3.seconds) should be(
        ByteString(
          0x24, 0x8d, 0x6a, 0x61,
          0xd2, 0x06, 0x38, 0xb8,
          0xe5, 0xc0, 0x26, 0x93,
          0x0c, 0x3e, 0x60, 0x39,
          0xa3, 0x3c, 0xe4, 0x59,
          0x64, 0xff, 0x21, 0x67,
          0xf6, 0xec, 0xed, 0xd4,
          0x19, 0xdb, 0x06, 0xc1))

    }

  }

}
