/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.remote.MessageSerializer
import akka.remote.WireFormats.{ TraceContext, TraceEnvelope }
import akka.serialization.Serializer
import akka.trace.{ MultiTracer, TracedMessage, Tracer }
import com.google.protobuf.ByteString

/**
 * Serialize traced message (wrapper that adds trace context to a message).
 */
class TracedMessageSerializer(system: ExtendedActorSystem) extends Serializer {

  def identifier: Int = 10

  def includeManifest: Boolean = false

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case traced: TracedMessage ⇒ serializeTracedMessage(traced)
    case _                     ⇒ throw new IllegalArgumentException(s"Expected to serialize TracedMessage but got ${obj.getClass.getName}")
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val traced = deserializeTracedMessage(bytes)
    if (system.hasTracer && nonEmpty(traced.context)) traced else ref(traced.message)
  }

  private def nonEmpty(context: Any) = context != Tracer.emptyContext

  private def ref(message: Any): AnyRef = message.asInstanceOf[AnyRef]

  private def serializeTracedMessage(traced: TracedMessage): Array[Byte] = {
    val builder = TraceEnvelope.newBuilder
    builder.setMessage(MessageSerializer.serialize(system, ref(traced.message)))
    serializeContext(traced.context) foreach {
      case (id, context) ⇒
        val contextBuilder = TraceContext.newBuilder
        contextBuilder.setTracerId(id)
        contextBuilder.setContext(ByteString.copyFrom(context))
        builder.addContexts(contextBuilder)
    }
    builder.build.toByteArray
  }

  private def serializeContext(context: Any): Seq[(Int, Array[Byte])] = {
    system.tracer match {
      case multi: MultiTracer ⇒ multi.serializeContexts(system, context)
      case single ⇒
        if (nonEmpty(context))
          Seq(single.identifier -> single.serializeContext(system, context))
        else
          Seq.empty
    }
  }

  private def deserializeTracedMessage(bytes: Array[Byte]): TracedMessage = {
    import scala.collection.JavaConverters._
    val traceEnvelope = TraceEnvelope.parseFrom(bytes)
    val message = MessageSerializer.deserialize(system, traceEnvelope.getMessage)
    val context = deserializeContext(traceEnvelope.getContextsList.asScala)
    TracedMessage(message, context)
  }

  private def deserializeContext(contextList: Seq[TraceContext]): Any = {
    val contexts = (contextList map { c ⇒ (c.getTracerId, c.getContext.toByteArray) }).toMap
    system.tracer match {
      case multi: MultiTracer ⇒ multi.deserializeContexts(system, contexts)
      case single ⇒
        if (contexts.isDefinedAt(single.identifier))
          single.deserializeContext(system, contexts(single.identifier))
        else
          Tracer.emptyContext
    }
  }
}
