package docs.stream.cookbook

import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeByteStrings extends RecipeSpec {

  "Recipes for bytestring streams" must {

    "have a working chunker" in {
      val rawBytes = Source(List(ByteString(1, 2), ByteString(3), ByteString(4, 5, 6), ByteString(7, 8, 9)))
      val ChunkLimit = 2

      //#bytestring-chunker
      import akka.stream.stage._

      class Chunker(val chunkSize: Int) extends PushPullStage[ByteString, ByteString] {
        private var buffer = ByteString.empty

        override def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
          buffer ++= elem
          emitChunkOrPull(ctx)
        }

        override def onPull(ctx: Context[ByteString]): SyncDirective = emitChunkOrPull(ctx)

        private def emitChunkOrPull(ctx: Context[ByteString]): SyncDirective = {
          if (buffer.isEmpty) ctx.pull()
          else {
            val (emit, nextBuffer) = buffer.splitAt(chunkSize)
            buffer = nextBuffer
            ctx.push(emit)
          }
        }

      }

      val chunksStream = rawBytes.transform(() => new Chunker(ChunkLimit))
      //#bytestring-chunker

      val chunksFuture = chunksStream.grouped(10).runWith(Sink.head)

      val chunks = Await.result(chunksFuture, 3.seconds)

      chunks.forall(_.size <= 2) should be(true)
      chunks.fold(ByteString())(_ ++ _) should be(ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9))
    }

    "have a working bytes limiter" in {
      val SizeLimit = 9

      //#bytes-limiter
      import akka.stream.stage._
      class ByteLimiter(val maximumBytes: Long) extends PushStage[ByteString, ByteString] {
        private var count = 0

        override def onPush(chunk: ByteString, ctx: Context[ByteString]): SyncDirective = {
          count += chunk.size
          if (count > maximumBytes) ctx.fail(new IllegalStateException("Too much bytes"))
          else ctx.push(chunk)
        }
      }

      val limiter = Flow[ByteString].transform(() => new ByteLimiter(SizeLimit))
      //#bytes-limiter

      val bytes1 = Source(List(ByteString(1, 2), ByteString(3), ByteString(4, 5, 6), ByteString(7, 8, 9)))
      val bytes2 = Source(List(ByteString(1, 2), ByteString(3), ByteString(4, 5, 6), ByteString(7, 8, 9, 10)))

      Await.result(bytes1.via(limiter).grouped(10).runWith(Sink.head), 3.seconds)
        .fold(ByteString())(_ ++ _) should be(ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9))

      an[IllegalStateException] must be thrownBy {
        Await.result(bytes2.via(limiter).grouped(10).runWith(Sink.head), 3.seconds)
      }
    }

    "demonstrate compacting" in {

      val data = Source(List(ByteString(1, 2), ByteString(3), ByteString(4, 5, 6), ByteString(7, 8, 9)))

      //#compacting-bytestrings
      val compacted: Source[ByteString, Unit] = data.map(_.compact)
      //#compacting-bytestrings

      Await.result(compacted.grouped(10).runWith(Sink.head), 3.seconds).forall(_.isCompact) should be(true)
    }

  }

}
