package akka.testkit.scaladsl

import java.nio.file._
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util

import akka.actor.dungeon.ChildrenContainer
import akka.actor.{ ActorPath, ActorRef, ActorRefProvider, ActorRefScope, ActorRefWithCell, ActorSystem, ActorSystemImpl, Cell, ChildStats, ExtendedActorSystem, InternalActorRef, Props }
import akka.dispatch.Envelope
import akka.dispatch.sysmsg.SystemMessage
import akka.serialization.{ SerializationExtension, Serializer, SerializerWithStringManifest }

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.reflect.ClassTag

/**
 *
 * @param failOnNonStringManifestSerializer Fail the test if the serializer passed in or resolved is not a
 * @param verifyLookedUpSerializer If an explicit serializer is provided, each verify will also look up the serializer
 *                                 from the actor system and verify that it is the same or fail the test if not.
 * @param checkBackwardBinaryCompatibility Save serialized data for the verified objects in the filesystem, and if previous
 *                                         versions exist, deserialize that data and compare with the passd in object.
 * @param backwardBinaryCompatibilityRootDir
 */
case class SerializerTestKitSettings(
  failOnNonStringManifestSerializer:  Boolean = true,
  verifyLookedUpSerializer:           Boolean = true,
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

  // minimal dummy implementation allowing for serialization like the actorref was realy but nothing else
  private final case class StableDummyActorRef(override val path: ActorPath)(implicit _system: ActorSystem) extends ActorRefWithCell with ActorRefScope {
    def system: ActorSystem = _system
    val underlying: Cell = new Cell {
      def parent: InternalActorRef = ???
      def getChildByName(name: String): Option[ChildStats] = ???
      private[akka] def isTerminated = ???
      def sendSystemMessage(msg: SystemMessage): Unit = ???
      def hasMessages: Boolean = ???
      def numberOfMessages: Int = ???
      def resume(causedByFailure: Throwable): Unit = ???
      def suspend(): Unit = ???
      def systemImpl: ActorSystemImpl = ???
      def restart(cause: Throwable): Unit = ???
      def start(): this.type = ???
      def sendMessage(msg: Envelope): Unit = ???
      def props: Props = ???
      def isLocal: Boolean = ???
      def getSingleChild(name: String): InternalActorRef = ???
      def system: ActorSystem = _system
      def stop(): Unit = ???
      def self: ActorRef = ???
      def childrenRefs: ChildrenContainer = ???
    }
    def children: immutable.Iterable[ActorRef] = ???
    def getSingleChild(name: String): InternalActorRef = ???
    def start(): Unit = ???
    def suspend(): Unit = ???
    def restart(cause: Throwable): Unit = ???
    def resume(causedByFailure: Throwable): Unit = ???
    def stop(): Unit = ???
    def sendSystemMessage(message: SystemMessage): Unit = ???
    def provider: ActorRefProvider = ???
    def getParent: InternalActorRef = ???
    def getChild(name: Iterator[String]): InternalActorRef = ???
    private[akka] def isTerminated = ???
    def isLocal: Boolean = ???
    def !(message: Any)(implicit sender: ActorRef): Unit = ???

  }

  def stableDummyActorRef(path: ActorPath)(implicit system: ActorSystem): ActorRef = new StableDummyActorRef(path)

}

/**
 * @param explicitSerializer The serializer to use or None to resolve the serializer through the given actor system
 */
final class SerializerTestKit(settings: SerializerTestKitSettings, explicitSerializer: Option[Serializer])(implicit system: ActorSystem) {

  def serializer(obj: AnyRef) = explicitSerializer.getOrElse(SerializationExtension(system).findSerializerFor(obj))

  def verify(original: AnyRef): Unit = verify(original, "")

  def verify[T <: AnyRef](original: T, variationId: String): T = {
    val serializerToUse = serializer(original)
    serializerToUse match {
      case ser: SerializerWithStringManifest ⇒
        val manifest = ser.manifest(original)
        val bytes = ser.toBinary(original)
        val result = ser.fromBinary(bytes, manifest)

        if (result != original) {
          throw new AssertionError(s"Serialized and deserialized version of ${original.getClass} does not match original." +
            s"\n    Expected: [$original]\n    was:      [$result]")
        }
        if (settings.verifyLookedUpSerializer)
          verifyConfiguredSerializer(original)
        if (settings.checkBackwardBinaryCompatibility)
          verifyBinaryCompatibility(ser, manifest, variationId, bytes)

        result.asInstanceOf[T]

      case _ ⇒
        if (settings.failOnNonStringManifestSerializer)
          throw new AssertionError(s"Serializer for ${original.getClass} should be a SerializerWithStringManifest")

        val bytes = serializerToUse.toBinary(original)
        val result = serializerToUse.fromBinary(bytes, original.getClass)

        if (result != original)
          throw new AssertionError(s"Serialized and deserialized version of ${original.getClass} does not match original." +
            s"\n    Expected: [$original]\n    was:      [$result]")
        if (settings.checkBackwardBinaryCompatibility)
          throw new UnsupportedOperationException("Checking serialized data for binary comp only supported for SerializerWithStringManifest")

        result.asInstanceOf[T]
    }
  }

  private def verifyConfiguredSerializer(original: AnyRef) = {
    explicitSerializer match {
      case Some(expected) ⇒
        val lookedUp = SerializationExtension(system).findSerializerFor(original)
        if (expected.getClass != lookedUp.getClass)
          throw new AssertionError(s"Expected the system to return serializer to be of type [${expected.getClass}] " +
            s"for [$original] but was [${lookedUp.getClass}]")
      case None ⇒ // can't check when no serializer explicitly set
    }
  }

  def verifyBinaryCompatibility(serializerToUse: SerializerWithStringManifest, manifest: String, variationId: String, currentBytes: Array[Byte]): Unit = {
    val versionsDirectory = versionsDirectoryFor(serializerToUse, manifest, variationId)

    def writeNewSerializedForm(bytes: Array[Byte]) = {
      val filename = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()).replace(':', '_')
      val path = versionsDirectory.resolve(filename)
      println(s"Serialized data for serializer ${serializerToUse.identifier}, " +
        s"manifest: $manifest, variationId: $variationId changed. " +
        s"Creating new file with serialized data in $path, this should be included in your VCS. " +
        "Renaming the file with your next application version may be useful.")
      Files.write(path, bytes)
    }

    if (!Files.exists(versionsDirectory)) {
      // this object has never been serialized before
      println(s"Previously unseen serialized object, creating directory $versionsDirectory")
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

  /**
   *
   * @param manifest
   * @param variationId
   * @param classTag
   * @tparam T
   * @return
   */
  def deserializeOldVersions[T](manifest: String, variationId: String)(implicit classTag: ClassTag[T]): immutable.Seq[(T, String)] = {
    val serializer = explicitSerializer.collect {
      case str: SerializerWithStringManifest ⇒ str
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
        case t: T ⇒ (t, file.getFileName.toString)
        case other ⇒ throw new AssertionError(s"Expected deserialized values to be of type [${classTag.runtimeClass}] " +
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
