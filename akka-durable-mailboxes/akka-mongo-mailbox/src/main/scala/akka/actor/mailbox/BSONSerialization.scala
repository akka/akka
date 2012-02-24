/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import java.io.InputStream

import org.bson.collection.BSONDocument
import org.bson.io.BasicOutputBuffer
import org.bson.io.OutputBuffer
import org.bson.SerializableBSONObject
import org.bson.BSONSerializer
import org.bson.DefaultBSONDeserializer
import org.bson.DefaultBSONSerializer

import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote.MessageSerializer
import akka.actor.ExtendedActorSystem

class BSONSerializableMessageQueue(system: ExtendedActorSystem) extends SerializableBSONObject[MongoDurableMessage] {

  protected[akka] def serializeDurableMsg(msg: MongoDurableMessage)(implicit serializer: BSONSerializer) = {

    // TODO - Skip the whole map creation step for performance, fun, and profit! (Needs Salat)
    val b = Map.newBuilder[String, Any]
    b += "_id" -> msg._id
    b += "ownerPath" -> msg.ownerPath
    b += "senderPath" -> msg.sender.path.toString

    /**
     * TODO - Figure out a way for custom serialization of the message instance
     * TODO - Test if a serializer is registered for the message and if not, use toByteString
     */
    val msgData = MessageSerializer.serialize(system, msg.message.asInstanceOf[AnyRef])
    b += "message" -> new org.bson.types.Binary(0, msgData.toByteArray)
    val doc = b.result
    serializer.putObject(doc)
  }

  /*
   * TODO - Implement some object pooling for the Encoders/decoders
   */
  def encode(msg: MongoDurableMessage, out: OutputBuffer) = {
    implicit val serializer = new DefaultBSONSerializer
    serializer.set(out)
    serializeDurableMsg(msg)
    serializer.done
  }

  def encode(msg: MongoDurableMessage): Array[Byte] = {
    implicit val serializer = new DefaultBSONSerializer
    val buf = new BasicOutputBuffer
    serializer.set(buf)
    serializeDurableMsg(msg)
    val bytes = buf.toByteArray
    serializer.done
    bytes
  }

  def decode(in: InputStream): MongoDurableMessage = {
    val deserializer = new DefaultBSONDeserializer
    // TODO - Skip the whole doc step for performance, fun, and profit! (Needs Salat / custom Deser)
    val doc = deserializer.decodeAndFetch(in).asInstanceOf[BSONDocument]
    val msgData = MessageProtocol.parseFrom(doc.as[org.bson.types.Binary]("message").getData)
    val msg = MessageSerializer.deserialize(system, msgData)
    val ownerPath = doc.as[String]("ownerPath")
    val senderPath = doc.as[String]("senderPath")
    val sender = system.actorFor(senderPath)

    MongoDurableMessage(ownerPath, msg, sender)
  }

  def checkObject(msg: MongoDurableMessage, isQuery: Boolean = false) = {} // object expected to be OK with this message type.

  def checkKeys(msg: MongoDurableMessage) {} // keys expected to be OK with this message type.

  /**
   * Checks for an ID and generates one.
   * Not all implementers will need this, but it gets invoked nonetheless
   * as a signal to BSONDocument, etc implementations to verify an id is there
   * and generate one if needed.
   */
  def checkID(msg: MongoDurableMessage) = msg // OID already generated in wrapper message

  def _id(msg: MongoDurableMessage): Option[AnyRef] = Some(msg._id)
}

// vim: set ts=2 sw=2 sts=2 et:
