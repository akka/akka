package docs.stream.cookbook

import java.security.MessageDigest

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
      def digestCalculator(algorithm: String) = new PushPullStage[ByteString, ByteString] {
        val digest = MessageDigest.getInstance(algorithm)

        override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
          digest.update(chunk.toArray)
          ctx.pull()
        }

        override def onPull(ctx: Context[ByteString]): SyncDirective = {
          if (ctx.isFinishing) ctx.pushAndFinish(ByteString(digest.digest()))
          else ctx.pull()
        }

        override def onUpstreamFinish(ctx: Context[ByteString]): TerminationDirective = {
          // If the stream is finished, we need to emit the last element in the onPull block.
          // It is not allowed to directly emit elements from a termination block
          // (onUpstreamFinish or onUpstreamFailure)
          ctx.absorbTermination()
        }
      }

      val digest: Source[ByteString, Unit] = data.transform(() => digestCalculator("SHA-256"))
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
