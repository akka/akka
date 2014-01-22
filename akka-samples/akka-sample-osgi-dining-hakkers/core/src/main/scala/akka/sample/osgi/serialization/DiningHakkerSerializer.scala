package akka.sample.osgi.serialization

import akka.serialization.Serializer
import akka.actor.ExtendedActorSystem
import akka.serialization.Serialization
import akka.serialization.SerializationExtension

class DiningHakkerSerializer(val system: ExtendedActorSystem) extends Serializer {

  override def includeManifest: Boolean = true

  override def identifier = 98765

  lazy val javaSerializer = SerializationExtension(system).findSerializerFor(classOf[java.io.Serializable])

  def toBinary(obj: AnyRef): Array[Byte] = {
    javaSerializer.toBinary(obj)
  }

  def fromBinary(bytes: Array[Byte],
                 clazz: Option[Class[_]]): AnyRef = {
    javaSerializer.fromBinary(bytes, clazz)
  }

}
