/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.serialization

import akka.serialization.{ SerializationExtension, Serializer }
import akka.actor._
import scala.annotation.tailrec
import akka.remote.ContainerFormats
import akka.remote.ContainerFormats.SelectionEnvelope
import akka.remote.WireFormats.SerializedMessage
import com.google.protobuf.ByteString
import akka.actor.SelectChildPattern
import akka.actor.SelectParent
import akka.actor.SelectChildName
import scala.Some
import java.util.regex.Pattern

class MessageContainerSerializer(val system: ExtendedActorSystem) extends Serializer {

  def identifier: Int = 6

  def includeManifest: Boolean = false

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case path: SelectionPath ⇒
      val builder = ContainerFormats.SelectionEnvelope.newBuilder()
      serializeSelectionPath(path, builder)
      builder.build().toByteArray
    case _ ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${obj.getClass.getName}]")
  }

  import ContainerFormats.PatternType._

  @tailrec
  private def serializeSelectionPath(path: Any, builder: SelectionEnvelope.Builder): Unit = path match {
    case SelectChildName(name, next) ⇒
      serializeSelectionPath(next, builder.addPattern(buildPattern(Some(name), CHILD_NAME)))
    case SelectChildPattern(pattern, next) ⇒
      serializeSelectionPath(next, builder.addPattern(buildPattern(Some(pattern.toString), CHILD_PATTERN)))
    case SelectParent(next) ⇒
      serializeSelectionPath(next, builder.addPattern(buildPattern(None, PARENT)))
    case message: AnyRef ⇒
      val serializer = SerializationExtension(system).findSerializerFor(message)
      builder
        .setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(message)))
        .setSerializerId(serializer.identifier)
      if (serializer.includeManifest)
        builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
      builder
  }

  private def buildPattern(matcher: Option[String], tpe: ContainerFormats.PatternType): ContainerFormats.Selection.Builder = {
    val builder = ContainerFormats.Selection.newBuilder().setType(tpe)
    matcher foreach builder.setMatcher
    builder
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val selectionEnvelope = ContainerFormats.SelectionEnvelope.parseFrom(bytes)
    val msg = SerializationExtension(system).deserialize(
      selectionEnvelope.getEnclosedMessage.toByteArray,
      selectionEnvelope.getSerializerId,
      if (selectionEnvelope.hasMessageManifest)
        Some(system.dynamicAccess.getClassFor[AnyRef](selectionEnvelope.getMessageManifest.toStringUtf8).get) else None).get

    @tailrec
    def reconstruct(remaining: Seq[ContainerFormats.Selection], nextLink: AnyRef): AnyRef = remaining match {
      case Nil ⇒ nextLink
      case sel :: tail ⇒
        val next = sel.getType match {
          case CHILD_NAME    ⇒ SelectChildName(sel.getMatcher, nextLink)
          case CHILD_PATTERN ⇒ SelectChildPattern(Pattern.compile(sel.getMatcher), nextLink)
          case PARENT        ⇒ SelectParent(nextLink)
        }
        reconstruct(tail, next)
    }

    import scala.collection.JavaConverters._
    reconstruct(selectionEnvelope.getPatternList.asScala.toList.reverse, msg)
  }
}
