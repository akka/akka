package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }

import akka.testkit.AkkaSpec
import akka.util.ByteString

class EnvelopeBufferSpec extends AkkaSpec {

  object TestCompressor extends LiteralCompressionTable {
    val refToIdx = Map(
      "compressable0" → 0,
      "compressable1" → 1,
      "reallylongcompressablestring" → 2)
    val idxToRef = refToIdx.map(_.swap)

    val serializerToIdx = Map(
      "serializer0" → 0,
      "serializer1" → 1)
    val idxToSer = serializerToIdx.map(_.swap)

    val manifestToIdx = Map(
      "manifest0" → 0,
      "manifest1" → 1)
    val idxToManifest = manifestToIdx.map(_.swap)

    override def compressActorRef(ref: String): Int = refToIdx.getOrElse(ref, -1)
    override def decompressActorRef(idx: Int): String = idxToRef(idx)
    override def compressClassManifest(manifest: String): Int = manifestToIdx.getOrElse(manifest, -1)
    override def decompressClassManifest(idx: Int): String = idxToManifest(idx)
  }

  "EnvelopeBuffer" must {
    val headerIn = HeaderBuilder(TestCompressor)
    val headerOut = HeaderBuilder(TestCompressor)

    val byteBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
    val envelope = new EnvelopeBuffer(byteBuffer)

    "be able to encode and decode headers with compressed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = "compressable0"
      headerIn.recipientActorRef = "compressable1"
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(EnvelopeBuffer.LiteralsSectionOffset) // Fully compressed header

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef should ===("compressable0")
      headerOut.recipientActorRef should ===("compressable1")
      headerOut.manifest should ===("manifest1")
    }

    "be able to encode and decode headers with uncompressed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = "uncompressable0"
      headerIn.recipientActorRef = "uncompressable11"
      headerIn.manifest = "uncompressable3333"

      val expectedHeaderLength =
        EnvelopeBuffer.LiteralsSectionOffset + // Constant header part
          2 + headerIn.senderActorRef.length + // Length field + literal
          2 + headerIn.recipientActorRef.length + // Length field + literal
          2 + headerIn.manifest.length // Length field + literal

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(expectedHeaderLength)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef should ===("uncompressable0")
      headerOut.recipientActorRef should ===("uncompressable11")
      headerOut.manifest should ===("uncompressable3333")
    }

    "be able to encode and decode headers with mixed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = "reallylongcompressablestring"
      headerIn.recipientActorRef = "uncompressable1"
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.recipientActorRef.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef should ===("reallylongcompressablestring")
      headerOut.recipientActorRef should ===("uncompressable1")
      headerOut.manifest should ===("manifest1")

      headerIn.version = 3
      headerIn.uid = Long.MinValue
      headerIn.serializer = -1
      headerIn.senderActorRef = "uncompressable0"
      headerIn.recipientActorRef = "reallylongcompressablestring"
      headerIn.manifest = "longlonglongliteralmanifest"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.senderActorRef.length +
          2 + headerIn.manifest.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(3)
      headerOut.uid should ===(Long.MinValue)
      headerOut.serializer should ===(-1)
      headerOut.senderActorRef should ===("uncompressable0")
      headerOut.recipientActorRef should ===("reallylongcompressablestring")
      headerOut.manifest should ===("longlonglongliteralmanifest")
    }

    "be able to encode and decode headers with mixed literals and payload" in {
      val payload = ByteString("Hello Artery!")

      headerIn.version = 1
      headerIn.uid = 42
      headerIn.serializer = 4
      headerIn.senderActorRef = "reallylongcompressablestring"
      headerIn.recipientActorRef = "uncompressable1"
      headerIn.manifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.put(payload.toByteBuffer)
      envelope.byteBuffer.flip()

      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef should ===("reallylongcompressablestring")
      headerOut.recipientActorRef should ===("uncompressable1")
      headerOut.manifest should ===("manifest1")

      ByteString.fromByteBuffer(envelope.byteBuffer) should ===(payload)
    }

  }

}
