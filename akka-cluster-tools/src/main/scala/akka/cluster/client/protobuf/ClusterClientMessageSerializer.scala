/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.client.protobuf

import scala.collection.JavaConverters._
import akka.actor.ExtendedActorSystem
import akka.serialization.BaseSerializer
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.cluster.client.ClusterReceptionist
import akka.cluster.client.protobuf.msg.{ ClusterClientMessages ⇒ cm }
import java.io.NotSerializableException

/**
 * INTERNAL API: Serializer of ClusterClient messages.
 */
private[akka] class ClusterClientMessageSerializer(val system: ExtendedActorSystem)
  extends SerializerWithStringManifest with BaseSerializer {
  import ClusterReceptionist.Internal._

  private lazy val serialization = SerializationExtension(system)

  private val ContactsManifest = "A"
  private val GetContactsManifest = "B"
  private val HeartbeatManifest = "C"
  private val HeartbeatRspManifest = "D"

  private val emptyByteArray = Array.empty[Byte]

  private val fromBinaryMap = collection.immutable.HashMap[String, Array[Byte] ⇒ AnyRef](
    ContactsManifest → contactsFromBinary,
    GetContactsManifest → { _ ⇒ GetContacts },
    HeartbeatManifest → { _ ⇒ Heartbeat },
    HeartbeatRspManifest → { _ ⇒ HeartbeatRsp })

  override def manifest(obj: AnyRef): String = obj match {
    case _: Contacts  ⇒ ContactsManifest
    case GetContacts  ⇒ GetContactsManifest
    case Heartbeat    ⇒ HeartbeatManifest
    case HeartbeatRsp ⇒ HeartbeatRspManifest
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case m: Contacts  ⇒ contactsToProto(m).toByteArray
    case GetContacts  ⇒ emptyByteArray
    case Heartbeat    ⇒ emptyByteArray
    case HeartbeatRsp ⇒ emptyByteArray
    case _ ⇒
      throw new IllegalArgumentException(s"Can't serialize object of type ${obj.getClass} in [${getClass.getName}]")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    fromBinaryMap.get(manifest) match {
      case Some(f) ⇒ f(bytes)
      case None ⇒ throw new NotSerializableException(
        s"Unimplemented deserialization of message with manifest [$manifest] in [${getClass.getName}]")
    }

  private def contactsToProto(m: Contacts): cm.Contacts =
    cm.Contacts.newBuilder().addAllContactPoints(m.contactPoints.asJava).build()

  private def contactsFromBinary(bytes: Array[Byte]): Contacts = {
    val m = cm.Contacts.parseFrom(bytes)
    Contacts(m.getContactPointsList.asScala.toVector)
  }

}
