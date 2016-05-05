package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }

import akka.testkit.AkkaSpec
import akka.util.ByteString

class EnvelopeBufferSpec extends AkkaSpec {

  object TestCompressor extends LiteralCompressionTable {
    val refToIdx = Map(
      "compressable0" -> 0,
      "compressable1" -> 1,
      "reallylongcompressablestring" -> 2)
    val idxToRef = refToIdx.map(_.swap)

    val serializerToIdx = Map(
      "serializer0" -> 0,
      "serializer1" -> 1)
    val idxToSer = serializerToIdx.map(_.swap)

    val manifestToIdx = Map(
      "manifest0" -> 0,
      "manifest1" -> 1)
    val idxToManifest = manifestToIdx.map(_.swap)

    override def compressActorRef(ref: String): Int = refToIdx.getOrElse(ref, -1)
    override def decompressActorRef(idx: Int): String = idxToRef(idx)
    override def compressSerializer(serializer: String): Int = serializerToIdx.getOrElse(serializer, -1)
    override def decompressSerializer(idx: Int): String = idxToSer(idx)
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
      headerIn.senderActorRef = "compressable0"
      headerIn.recipientActorRef = "compressable1"
      headerIn.serializer = "serializer0"
      headerIn.classManifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(EnvelopeBuffer.LiteralsSectionOffset) // Fully compressed header

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.senderActorRef should ===("compressable0")
      headerOut.recipientActorRef should ===("compressable1")
      headerOut.serializer should ===("serializer0")
      headerOut.classManifest should ===("manifest1")
    }

    "be able to encode and decode headers with uncompressed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.senderActorRef = "uncompressable0"
      headerIn.recipientActorRef = "uncompressable11"
      headerIn.serializer = "uncompressable222"
      headerIn.classManifest = "uncompressable3333"

      val expectedHeaderLength =
        EnvelopeBuffer.LiteralsSectionOffset + // Constant header part
          2 + headerIn.senderActorRef.length + // Length field + literal
          2 + headerIn.recipientActorRef.length + // Length field + literal
          2 + headerIn.serializer.length + // Length field + literal
          2 + headerIn.classManifest.length // Length field + literal

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(expectedHeaderLength)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.senderActorRef should ===("uncompressable0")
      headerOut.recipientActorRef should ===("uncompressable11")
      headerOut.serializer should ===("uncompressable222")
      headerOut.classManifest should ===("uncompressable3333")
    }

    "be able to encode and decode headers with mixed literals" in {
      headerIn.version = 1
      headerIn.uid = 42
      headerIn.senderActorRef = "reallylongcompressablestring"
      headerIn.recipientActorRef = "uncompressable1"
      headerIn.serializer = "longuncompressedserializer"
      headerIn.classManifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.recipientActorRef.length +
          2 + headerIn.serializer.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.senderActorRef should ===("reallylongcompressablestring")
      headerOut.recipientActorRef should ===("uncompressable1")
      headerOut.serializer should ===("longuncompressedserializer")
      headerOut.classManifest should ===("manifest1")

      headerIn.version = 3
      headerIn.uid = Int.MinValue
      headerIn.senderActorRef = "uncompressable0"
      headerIn.recipientActorRef = "reallylongcompressablestring"
      headerIn.serializer = "serializer0"
      headerIn.classManifest = "longlonglongliteralmanifest"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.senderActorRef.length +
          2 + headerIn.classManifest.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(3)
      headerOut.uid should ===(Int.MinValue)
      headerOut.senderActorRef should ===("uncompressable0")
      headerOut.recipientActorRef should ===("reallylongcompressablestring")
      headerOut.serializer should ===("serializer0")
      headerOut.classManifest should ===("longlonglongliteralmanifest")
    }

    "be able to encode and decode headers with mixed literals and payload" in {
      val payload = ByteString("Hello Artery!")

      headerIn.version = 1
      headerIn.uid = 42
      headerIn.senderActorRef = "reallylongcompressablestring"
      headerIn.recipientActorRef = "uncompressable1"
      headerIn.serializer = "serializer1"
      headerIn.classManifest = "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.put(payload.toByteBuffer)
      envelope.byteBuffer.flip()

      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.senderActorRef should ===("reallylongcompressablestring")
      headerOut.recipientActorRef should ===("uncompressable1")
      headerOut.serializer should ===("serializer1")
      headerOut.classManifest should ===("manifest1")

      ByteString.fromByteBuffer(envelope.byteBuffer) should ===(payload)
    }

  }

}
