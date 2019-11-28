/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import akka.remote.artery.protobuf.{ TestMessages => proto }
import akka.protobufv3.internal.ByteString

object TestMessage {
  final case class Item(id: Long, name: String)
}

final case class TestMessage(
    id: Long,
    name: String,
    status: Boolean,
    description: String,
    payload: Array[Byte],
    items: Vector[TestMessage.Item])

class TestMessageSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest {

  val TestMessageManifest = "A"

  override val identifier: Int = 101

  override def manifest(o: AnyRef): String =
    o match {
      case _: TestMessage => TestMessageManifest
    }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case msg: TestMessage =>
      val builder = proto.TestMessage
        .newBuilder()
        .setId(msg.id)
        .setName(msg.name)
        .setDescription(msg.description)
        .setStatus(msg.status)
        .setPayload(ByteString.copyFrom(msg.payload))
      msg.items.foreach { item =>
        builder.addItems(proto.Item.newBuilder().setId(item.id).setName(item.name))
      }
      builder.build().toByteArray()
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val protoMsg = proto.TestMessage.parseFrom(bytes)
    import akka.util.ccompat.JavaConverters._
    val items = protoMsg.getItemsList.asScala.map { item =>
      TestMessage.Item(item.getId, item.getName)
    }.toVector

    TestMessage(
      id = protoMsg.getId,
      name = protoMsg.getName,
      description = protoMsg.getDescription,
      status = protoMsg.getStatus,
      payload = protoMsg.getPayload.toByteArray(),
      items = items)
  }
}
