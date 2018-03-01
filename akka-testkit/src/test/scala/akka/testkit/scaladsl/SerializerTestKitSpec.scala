package akka.testkit.scaladsl

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.serialization.SerializerWithStringManifest
import akka.testkit.TestKit
import com.google.common.jimfs.{ Configuration, Jimfs }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.collection.JavaConverters._

class SerializerTestKitSpec extends TestKit(ActorSystem("SerializerTestKitSpec")) with WordSpecLike with Matchers with BeforeAndAfterAll {

  case object OnlyOneWay
  case object JustAManifest
  case object ThisOneWorks
  case class ActuallySerialized(text: String)
  class FaultySerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

    val StringManifest = "STR"
    val OnlyOneWayManifest = "OOW"
    val OnlyManifest = "OO"
    val ThisOneWorksManifest = "TOW"
    val ActuallySerializedManifest = "AS"

    override def identifier: Int = 666
    override def manifest(o: AnyRef): String = o match {
      case _: String             ⇒ StringManifest
      case OnlyOneWay            ⇒ OnlyOneWayManifest
      case JustAManifest         ⇒ OnlyManifest
      case ThisOneWorks          ⇒ ThisOneWorksManifest
      case _: ActuallySerialized ⇒ ActuallySerializedManifest
    }

    override def toBinary(o: AnyRef): Array[Byte] = o match {
      case str: String             ⇒ str.getBytes(StandardCharsets.UTF_8)
      case OnlyOneWay              ⇒ Array.emptyByteArray
      case ThisOneWorks            ⇒ Array.emptyByteArray
      case ActuallySerialized(str) ⇒ str.getBytes(StandardCharsets.UTF_8)
      // OO missing
      case _                       ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${o.getClass.getName}]")
    }

    override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
      case StringManifest             ⇒ new String(bytes, StandardCharsets.UTF_8) + "fault" // different deserialized result
      case ThisOneWorksManifest       ⇒ ThisOneWorks
      case ActuallySerializedManifest ⇒ ActuallySerialized(new String(bytes, StandardCharsets.UTF_8))
      // OOW is missing
      case _                          ⇒ throw new IllegalArgumentException(s"Cannot deserialize object with manifest [$manifest]")
    }
  }

  val fs = Jimfs.newFileSystem("SerializerTestKitSpec1", Configuration.unix())

  "The serializer testkit" should {

    "detect faulty serializers" in {
      val testKit = SerializerTestKit(eas ⇒ new FaultySerializer(eas))

      intercept[AssertionError] {
        testKit.verify("test")
      }

      intercept[IllegalArgumentException] {
        testKit.verify(OnlyOneWay)
      }

      intercept[IllegalArgumentException] {
        testKit.verify(JustAManifest)
      }

    }

    "write previously unseen serialized form to file" in {
      val path = fs.getPath("/unseen")
      Files.createDirectories(path)
      val testKit = SerializerTestKit(
        SerializerTestKitSettings(
          checkBackwardBinaryCompatibility = true,
          backwardBinaryCompatibilityRootDir = path
        ),
        eas ⇒ new FaultySerializer(eas)
      )

      testKit.verify(ThisOneWorks, "variation1")
      Files.list(path.resolve("666").resolve("TOW").resolve("variation1")).iterator().asScala.size should ===(1)
    }

    "write to file if serialized form changes" in {
      val path = fs.getPath("/changes")
      Files.createDirectories(path)
      val testKit = SerializerTestKit(
        SerializerTestKitSettings(
          checkBackwardBinaryCompatibility = true,
          backwardBinaryCompatibilityRootDir = path
        ),
        eas ⇒ new FaultySerializer(eas)
      )

      testKit.verify(ActuallySerialized("version1"), "variation1")
      Files.list(path.resolve("666").resolve("AS").resolve("variation1")).iterator().asScala.size should ===(1)

      Thread.sleep(1) // we use timestamp for naming, so make sure we get two files
      // we fake that the representation changed by sending in the same variation with a different object
      testKit.verify(ActuallySerialized("version2"), "variation1")
      Files.list(path.resolve("666").resolve("AS").resolve("variation1")).iterator().asScala.size should ===(2)

    }

    "allow for manual verification of old serialized objects" in {
      val path = fs.getPath("/old-serialized")
      Files.createDirectories(path)
      val testKit = SerializerTestKit(
        SerializerTestKitSettings(
          checkBackwardBinaryCompatibility = true,
          backwardBinaryCompatibilityRootDir = path
        ),
        eas ⇒ new FaultySerializer(eas)
      )

      testKit.verify(ActuallySerialized("version1"), "variation1")
      Thread.sleep(1) // we use timestamp for naming, so make sure we get two files
      // we fake that the representation changed by sending in the same variation with a different object
      testKit.verify(ActuallySerialized("version2"), "variation1")

      val oldVersions = testKit.deserializeOldVersions[ActuallySerialized]("AS", "variation1")
      oldVersions should have size (2)
      oldVersions.map(_._1.text).toSet should ===(Set("version1", "version2"))
    }

  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    fs.close()
  }
}
