package akka.testkit.scaladsl

import java.nio.file._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.serialization.{SerializationExtension, Serializer, SerializerWithStringManifest}

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.reflect.ClassTag

case class SerializerTestKitSettings(
  failOnNonStringManifestSerializer:  Boolean = true,
  checkBackwardBinaryCompatibility:   Boolean = false,
  backwardBinaryCompatibilityRootDir: Path    = Paths.get("src/test/serializer-testkit")
)

/**
 * A testkit to help verify serialization and serialization compatibility over time.
 *
 * For the wire compatibility part, set `settings.checkBackwardBinaryCompatibility` to `true` and make sure to
 * include the generated files in your VCS so that earlier versions of the serialized data is available for
 * verification and incompatibilities can be detected. By default the data is expected to live in `src/test/serializer-testkit`
 */
object SerializerTestKit {
  def apply()(implicit system: ActorSystem) = new SerializerTestKit(SerializerTestKitSettings(), None)
  def apply(settings: SerializerTestKitSettings)(implicit system: ActorSystem) = new SerializerTestKit(settings, None)
  def apply(serializer: ExtendedActorSystem ⇒ Serializer)(implicit system: ActorSystem) =
    new SerializerTestKit(SerializerTestKitSettings(), Some(serializer(system.asInstanceOf[ExtendedActorSystem])))
  def apply(settings: SerializerTestKitSettings, serializer: ExtendedActorSystem ⇒ Serializer)(implicit system: ActorSystem) =
    new SerializerTestKit(settings, Some(serializer(system.asInstanceOf[ExtendedActorSystem])))

}

/**
 * @param explicitSerializer The serializer to use or None to resolve the serializer through the given actor system
 */
final class SerializerTestKit(settings: SerializerTestKitSettings, explicitSerializer: Option[Serializer])(implicit system: ActorSystem) {

  def serializer(obj: AnyRef) = explicitSerializer.getOrElse(SerializationExtension(system).findSerializerFor(obj))

  def verify(obj: AnyRef): Unit = verify(obj, "")

  def verify(obj: AnyRef, variationId: String): Unit = {
    val serializerToUse = serializer(obj)
    serializerToUse match {
      case ser: SerializerWithStringManifest ⇒
        val manifest = ser.manifest(obj)
        val bytes = ser.toBinary(obj)

        val result = ser.fromBinary(bytes, manifest)

        if (result != obj) throw new AssertionError(s"Serialized and deserialized version of ${obj.getClass} does not match." +
          s"Expected: [$obj], was [$result]")
        if (settings.checkBackwardBinaryCompatibility)
          verifyBinaryCompatibility(ser, manifest, variationId, bytes)

      case _ ⇒
        if (settings.failOnNonStringManifestSerializer)
          throw new AssertionError(s"Serializer for ${obj.getClass} should be a SerializerWithStringManifest")

        val bytes = serializerToUse.toBinary(obj)
        val result = serializerToUse.fromBinary(bytes, obj.getClass)

        if (result != obj) throw new AssertionError(s"Serialized and deserialized version of ${obj.getClass} does not match.")
        if (settings.checkBackwardBinaryCompatibility)
          throw new UnsupportedOperationException("Checking serialized data for binary comp only supported for SerializerWithStringManifest")
    }
  }

  def verifyBinaryCompatibility(serializerToUse: SerializerWithStringManifest, manifest: String, variationId: String, currentBytes: Array[Byte]): Unit = {
    val versionsDirectory = versionsDirectoryFor(serializerToUse, manifest, variationId)

    def writeNewSerializedForm(bytes: Array[Byte]) = {
      val filename = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()).replace(':', '_')
      val path = versionsDirectory.resolve(filename)
      system.log.info(s"Serialized data for serializer ${serializerToUse.identifier}, " +
        s"manifest: $manifest, variationId: $variationId changed. " +
        s"Creating new file with serialized data in $path, this should be included in your VCS. " +
        "Renaming the file with your next application version may be useful.")
      Files.write(path, bytes)
    }

    if (!Files.exists(versionsDirectory)) {
      // this object has never been serialized before
      system.log.info(s"Previously unseen serialized object, creating directory $versionsDirectory")
      Files.createDirectories(versionsDirectory)
      writeNewSerializedForm(currentBytes)
    } else {
      // serialized before, verify that all those can still be deserialized
      for {
        file ← Files.list(versionsDirectory).iterator().asScala
      } {
        val oldBytes = Files.readAllBytes(file)

        // check that it can be deserialized
        // this will throw if not
        serializerToUse.fromBinary(oldBytes, manifest)

        // if it changed we need to create a new dump file
        if (!util.Arrays.equals(currentBytes, oldBytes)) {
          writeNewSerializedForm(currentBytes)
        }
        // if it didn't everything is fine

      }
    }
  }


  def deserializeOldVersions[T](manifest: String, variationId: String)(implicit classTag: ClassTag[T]): immutable.Seq[T] = {
    val serializer = explicitSerializer.collect {
      case str: SerializerWithStringManifest => str
    } getOrElse (throw new IllegalStateException("Only supported when run with an explicit serializer of type SerializerWithStringManifest"))
    val versionsDirectory = versionsDirectoryFor(serializer, manifest, variationId)
    // serialized before, verify that all those can still be deserialized
    for {
      file ← Files.list(versionsDirectory).iterator().asScala.toIndexedSeq
    } yield {
      val oldBytes = Files.readAllBytes(file)

      // check that it can be deserialized
      // this will throw if not
      serializer.fromBinary(oldBytes, manifest) match {
        case t: T => t
        case other => throw new AssertionError(s"Expected deserialized values to be of type [${classTag.runtimeClass}] " +
          s"but [${file.getFileName}] was deserialized to [${other.getClass}]")
      }
    }
  }

  private def versionsDirectoryFor(serializerToUse: SerializerWithStringManifest, manifest: String, variationId: String) = {
    settings.backwardBinaryCompatibilityRootDir
      .resolve(serializerToUse.identifier.toString)
      .resolve(manifest)
      .resolve(variationId)


  }
}
