/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import scala.collection.immutable
import akka.protobuf.ByteString
import akka.actor.ActorSelectionMessage
import akka.actor.ExtendedActorSystem
import akka.actor.SelectChildName
import akka.actor.SelectChildPattern
import akka.actor.SelectParent
import akka.actor.SelectionPathElement
import akka.remote.ContainerFormats
import akka.serialization.SerializationExtension
import akka.serialization.BaseSerializer
import akka.serialization.SerializerWithStringManifest

class MessageContainerSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  @deprecated("Use constructor with ExtendedActorSystem", "2.4")
  def this() = this(null)

  private lazy val serialization = SerializationExtension(system)

  // TODO remove this when deprecated this() is removed
  override val identifier: Int =
    if (system eq null) 6
    else identifierFromConfig

  def includeManifest: Boolean = false

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case sel: ActorSelectionMessage ⇒ serializeSelection(sel)
    case _                          ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${obj.getClass.getName}]")
  }

  import ContainerFormats.PatternType._

  private def serializeSelection(sel: ActorSelectionMessage): Array[Byte] = {
    val builder = ContainerFormats.SelectionEnvelope.newBuilder()
    val message = sel.msg.asInstanceOf[AnyRef]
    val serializer = serialization.findSerializerFor(message)
    builder.
      setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(message))).
      setSerializerId(serializer.identifier).
      setWildcardFanOut(sel.wildcardFanOut)

    serializer match {
      case ser2: SerializerWithStringManifest ⇒
        val manifest = ser2.manifest(message)
        if (manifest != "")
          builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
        if (serializer.includeManifest)
          builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))
    }

    sel.elements.foreach {
      case SelectChildName(name) ⇒
        builder.addPattern(buildPattern(Some(name), CHILD_NAME))
      case SelectChildPattern(patternStr) ⇒
        builder.addPattern(buildPattern(Some(patternStr), CHILD_PATTERN))
      case SelectParent ⇒
        builder.addPattern(buildPattern(None, PARENT))
    }

    builder.build().toByteArray
  }

  private def buildPattern(matcher: Option[String], tpe: ContainerFormats.PatternType): ContainerFormats.Selection.Builder = {
    val builder = ContainerFormats.Selection.newBuilder().setType(tpe)
    matcher foreach builder.setMatcher
    builder
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val selectionEnvelope = ContainerFormats.SelectionEnvelope.parseFrom(bytes)
    val manifest = if (selectionEnvelope.hasMessageManifest) selectionEnvelope.getMessageManifest.toStringUtf8 else ""
    val msg = serialization.deserialize(
      selectionEnvelope.getEnclosedMessage.toByteArray,
      selectionEnvelope.getSerializerId,
      manifest).get

    import scala.collection.JavaConverters._
    val elements: immutable.Iterable[SelectionPathElement] = selectionEnvelope.getPatternList.asScala.map { x ⇒
      x.getType match {
        case CHILD_NAME    ⇒ SelectChildName(x.getMatcher)
        case CHILD_PATTERN ⇒ SelectChildPattern(x.getMatcher)
        case PARENT        ⇒ SelectParent
      }

    }(collection.breakOut)
    val wildcardFanOut = if (selectionEnvelope.hasWildcardFanOut) selectionEnvelope.getWildcardFanOut else false
    ActorSelectionMessage(msg, elements, wildcardFanOut)
  }
}
