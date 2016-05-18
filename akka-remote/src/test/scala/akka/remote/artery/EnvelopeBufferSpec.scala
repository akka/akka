package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }

import akka.actor._
import akka.remote.artery.compress.CompressionTestUtils
import akka.testkit.AkkaSpec
import akka.util.{ OptionVal, ByteString }

class EnvelopeBufferSpec extends AkkaSpec {
  import CompressionTestUtils._

  object TestCompressor extends InboundCompression with OutboundCompression {
    val refToIdx: Map[ActorRef, Int] = Map(
      minimalRef("compressable0") → 0,
      minimalRef("compressable1") → 1,
      minimalRef("reallylongcompressablestring") → 2)
    val idxToRef: Map[Int, ActorRef] = refToIdx.map(_.swap)

    val serializerToIdx = Map(
      "serializer0" → 0,
      "serializer1" → 1)
    val idxToSer = serializerToIdx.map(_.swap)

    val manifestToIdx = Map(
      "manifest0" → 0,
      "manifest1" → 1)
    val idxToManifest = manifestToIdx.map(_.swap)

    override def allocateActorRefCompressionId(ref: ActorRef, id: Int): Unit = ??? // dynamic allocating not implemented here
    override def compressActorRef(ref: ActorRef): Int = refToIdx.getOrElse(ref, -1)
    override def hitActorRef(address: Address, ref: ActorRef): Unit = ()
    override def decompressActorRef(idx: Int): OptionVal[ActorRef] = OptionVal.Some(idxToRef(idx))

    override def allocateClassManifestCompressionId(manifest: String, id: Int): Unit = ??? // dynamic allocating not implemented here
    override def compressClassManifest(manifest: String): Int = manifestToIdx.getOrElse(manifest, -1)
    override def hitClassManifest(address: Address, manifest: String): Unit = ()
    override def decompressClassManifest(idx: Int) = OptionVal.Some(idxToManifest(idx))
  }

  "EnvelopeBuffer" must {
    val headerIn = HeaderBuilder.bothWays(TestCompressor, TestCompressor)
    val headerOut = HeaderBuilder.bothWays(TestCompressor, TestCompressor)

    val byteBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
    val envelope = new EnvelopeBuffer(byteBuffer)

    "be able to encode and decode headers with compressed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = minimalRef("compressable0")
      headerIn.recipientActorRef = minimalRef("compressable1")
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(EnvelopeBuffer.LiteralsSectionOffset) // Fully compressed header

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRefPath should ===("akka://EnvelopeBufferSpec/compressable0")
      headerOut.recipientActorRefPath should ===("akka://EnvelopeBufferSpec/compressable1")
      headerOut.manifest should ===("manifest1")
    }

    "be able to encode and decode headers with uncompressed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = minimalRef("uncompressable0")
      headerIn.recipientActorRef = minimalRef("uncompressable11")
      headerIn.manifest = "uncompressable3333"

      val expectedHeaderLength =
        EnvelopeBuffer.LiteralsSectionOffset + // Constant header part
          2 + headerIn.senderActorRefPath.length + // Length field + literal
          2 + headerIn.recipientActorRefPath.length + // Length field + literal
          2 + headerIn.manifest.length // Length field + literal

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(expectedHeaderLength)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRefPath should ===("akka://EnvelopeBufferSpec/uncompressable0")
      headerOut.recipientActorRefPath should ===("akka://EnvelopeBufferSpec/uncompressable11")
      headerOut.manifest should ===("uncompressable3333")
    }

    "be able to encode and decode headers with mixed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = minimalRef("reallylongcompressablestring")
      headerIn.recipientActorRef = minimalRef("uncompressable1")
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.recipientActorRefPath.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRefPath should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.recipientActorRefPath should ===("akka://EnvelopeBufferSpec/uncompressable1")
      headerOut.manifest should ===("manifest1")

      headerIn.version = 3
      headerIn.uid = Long.MinValue
      headerIn.serializer = -1
      headerIn.senderActorRef = minimalRef("uncompressable0")
      headerIn.recipientActorRef = minimalRef("reallylongcompressablestring")
      headerIn.manifest = "longlonglongliteralmanifest"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.senderActorRefPath.length +
          2 + headerIn.manifest.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(3)
      headerOut.uid should ===(Long.MinValue)
      headerOut.serializer should ===(-1)
      headerOut.senderActorRefPath should ===("akka://EnvelopeBufferSpec/uncompressable0")
      headerOut.recipientActorRefPath should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.manifest should ===("longlonglongliteralmanifest")
    }

    "be able to encode and decode headers with mixed literals and payload" in {
      val payload = ByteString("Hello Artery!")

      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = minimalRef("reallylongcompressablestring")
      headerIn.recipientActorRef = minimalRef("uncompressable1")
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.put(payload.toByteBuffer)
      envelope.byteBuffer.flip()

      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRefPath should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.recipientActorRefPath should ===("akka://EnvelopeBufferSpec/uncompressable1")
      headerOut.manifest should ===("manifest1")

      ByteString.fromByteBuffer(envelope.byteBuffer) should ===(payload)
    }

  }

}
