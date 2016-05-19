package akka.remote.artery

import akka.actor.ActorSystem

/**
 * INTERNAL API
 */
// FIXME: Dummy compression table, needs to be replaced by the real deal
// Currently disables all compression
private[remote] class Compression(system: ActorSystem) extends LiteralCompressionTable {
  // FIXME: Of course it is foolish to store this as String, but this is a stub
  val deadLettersString = system.deadLetters.path.toSerializationFormat

  override def compressActorRef(ref: String): Int = -1
  override def decompressActorRef(idx: Int): String = ???

  override def compressSerializer(serializer: String): Int = -1
  override def decompressSerializer(idx: Int): String = ???

  override def compressClassManifest(manifest: String): Int = -1
  override def decompressClassManifest(idx: Int): String = ???

}
