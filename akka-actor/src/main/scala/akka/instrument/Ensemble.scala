/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ DynamicAccess, ActorSystem, ActorRef }
import akka.event.EventStream
import akka.event.Logging.{ Warning, Error }
import com.typesafe.config.Config
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.hashing.MurmurHash3

/**
 * Implementation of RemoteInstrumentation that delegates to multiple instrumentations.
 * Contexts are stored as a sequence, and aligned with instrumentations when retrieved.
 * Efficient implementation using an array and manually inlined loops.
 */
final class Ensemble(dynamicAccess: DynamicAccess, config: Config, eventStream: EventStream) extends RemoteInstrumentation {

  // DO NOT MODIFY THIS ARRAY
  private[this] val instrumentations: Array[ActorInstrumentation] = {
    import collection.JavaConverters.collectionAsScalaIterableConverter
    val classes = config.getStringList("akka.instrumentations").asScala
    classes.zipWithIndex.map({
      case (instrumentation, index) ⇒
        ActorInstrumentation.create(instrumentation, dynamicAccess, config, eventStream, new Ensemble.MultiActorMetadata(index, classes.size))
    }).toArray
  }

  private[this] val length: Int = instrumentations.length

  private[this] val emptyContexts: Array[AnyRef] = Array.fill(length)(ActorInstrumentation.EmptyContext)

  override def access[T <: ActorInstrumentation](instrumentationClass: Class[T]): T =
    (if (instrumentationClass isInstance this) this else instrumentations.collectFirst {
      case t if t.access(instrumentationClass) ne null ⇒ t.access(instrumentationClass)
    }.getOrElse(null)).asInstanceOf[T]

  def findAll[T <: ActorInstrumentation](instrumentationClass: Class[T]): Seq[T] =
    instrumentations flatMap { i ⇒ Option(i.access(instrumentationClass)) }

  def findAll[T <: ActorInstrumentation](implicit tag: ClassTag[T]): Seq[T] =
    findAll(tag.runtimeClass.asInstanceOf[Class[T]])

  override def systemStarted(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).systemStarted(system)
      i += 1
    }
  }

  override def systemShutdown(system: ActorSystem): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).systemShutdown(system)
      i += 1
    }
  }

  override def actorCreated(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorCreated(actorRef)
      i += 1
    }
  }

  override def actorStarted(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorStarted(actorRef)
      i += 1
    }
  }

  override def actorStopped(actorRef: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).actorStopped(actorRef)
      i += 1
    }
  }

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    val contexts = Array.ofDim[AnyRef](length)
    var i = 0
    while (i < length) {
      contexts(i) = instrumentations(i).actorTold(actorRef, message, sender)
      i += 1
    }
    contexts
  }

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
    val contexts = if (context eq ActorInstrumentation.EmptyContext) emptyContexts else context.asInstanceOf[Array[AnyRef]]
    val localContexts = Array.ofDim[AnyRef](length)
    var i = 0
    while (i < length) {
      localContexts(i) = instrumentations(i).actorReceived(actorRef, message, sender, contexts(i))
      i += 1
    }
    localContexts
  }

  override def actorCompleted(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): Unit = {
    val contexts = if (context eq ActorInstrumentation.EmptyContext) emptyContexts else context.asInstanceOf[Array[AnyRef]]
    var i = 0
    while (i < length) {
      instrumentations(i).actorCompleted(actorRef, message, sender, contexts(i))
      i += 1
    }
  }

  override def clearContext(): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).clearContext()
      i += 1
    }
  }

  override def eventUnhandled(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventUnhandled(actorRef, message, sender)
      i += 1
    }
  }

  override def eventDeadLetter(actorRef: ActorRef, message: Any, sender: ActorRef): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventDeadLetter(actorRef, message, sender)
      i += 1
    }
  }

  override def eventLogWarning(actorRef: ActorRef, warning: Warning): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventLogWarning(actorRef, warning)
      i += 1
    }
  }

  override def eventLogError(actorRef: ActorRef, error: Error): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventLogError(actorRef, error)
      i += 1
    }
  }

  override def eventActorFailure(actorRef: ActorRef, cause: Throwable): Unit = {
    var i = 0
    while (i < length) {
      instrumentations(i).eventActorFailure(actorRef, cause)
      i += 1
    }
  }

  override val remoteIdentifier: Int = MurmurHash3.stringHash("Ensemble")

  override def remoteActorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef = {
    val contexts = Array.ofDim[AnyRef](length)
    var i = 0
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation ⇒
          contexts(i) = instrumentation.remoteActorTold(actorRef, message, sender)
        case _ ⇒ contexts(i) = ActorInstrumentation.EmptyContext
      }
      i += 1
    }
    contexts
  }

  override def remoteMessageSent(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: AnyRef): Array[Byte] = {
    val contexts = if (context eq ActorInstrumentation.EmptyContext) emptyContexts else context.asInstanceOf[Array[AnyRef]]
    var serializedContexts = List.empty[(Int, Array[Byte])]
    var i = 0
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation ⇒
          val serializedContext = instrumentation.remoteMessageSent(actorRef, message, sender, size, contexts(i))
          if (serializedContext ne null) serializedContexts = (instrumentation.remoteIdentifier -> serializedContext) :: serializedContexts
        case _ ⇒ // ignore non-remote instrumentation
      }
      i += 1
    }
    joinContexts(serializedContexts)
  }

  override def remoteMessageReceived(actorRef: ActorRef, message: Any, sender: ActorRef, size: Int, context: Array[Byte]): Unit = {
    val contexts = splitContexts(context)
    var i = 0
    while (i < length) {
      instrumentations(i) match {
        case instrumentation: RemoteInstrumentation if contexts.contains(instrumentation.remoteIdentifier) ⇒
          instrumentation.remoteMessageReceived(actorRef, message, sender, size, contexts(instrumentation.remoteIdentifier))
        case _ ⇒ // ignore non-remote instrumentation
      }
      i += 1
    }
  }

  private val byteOrder = java.nio.ByteOrder.BIG_ENDIAN

  private def joinContexts(contexts: List[(Int, Array[Byte])]): Array[Byte] = {
    @tailrec
    def writeContexts(writer: ByteBuffer, contexts: List[(Int, Array[Byte])]): Unit =
      if (contexts.nonEmpty) {
        val context = contexts.head
        writer.putInt(context._1)
        writer.putInt(context._2.length)
        writer.put(context._2)
        writeContexts(writer, contexts.tail)
      }

    val totalSize = (0 /: contexts) {
      case (size, (id, context)) ⇒ size + context.length + (2 * 4)
    }
    val context = Array.ofDim[Byte](totalSize)
    val writer = ByteBuffer.wrap(context).order(byteOrder)
    writeContexts(writer, contexts)
    context
  }

  private def splitContexts(context: Array[Byte]): Map[Int, Array[Byte]] = {
    @tailrec
    def readContexts(reader: ByteBuffer, map: Map[Int, Array[Byte]]): Map[Int, Array[Byte]] =
      if (reader.hasRemaining) {
        val id = reader.getInt
        val length = reader.getInt
        val context = Array.ofDim[Byte](length)
        reader.get(context)
        readContexts(reader, map.updated(id, context))
      } else map

    if (context eq RemoteInstrumentation.EmptySerializedContext) Map.empty
    else readContexts(ByteBuffer.wrap(context).order(byteOrder), Map.empty)
  }

}

object Ensemble {

  /**
   * ActorMetadata wrapper that accesses and stores at a particular index in an array.
   */
  class MultiActorMetadata(index: Int, size: Int) extends ActorMetadata {
    override def attachTo(actorRef: ActorRef, metadata: AnyRef): Unit = super.extractFrom(actorRef) match {
      case array: Array[AnyRef] ⇒ array(index) = metadata
      case _ ⇒
        val array = Array.ofDim[AnyRef](size)
        array(index) = metadata
        super.attachTo(actorRef, array)
    }

    override def extractFrom(actorRef: ActorRef): AnyRef = super.extractFrom(actorRef) match {
      case array: Array[AnyRef] ⇒ array(index)
      case _                    ⇒ null
    }

    override def removeFrom(actorRef: ActorRef): AnyRef = super.extractFrom(actorRef) match {
      case array: Array[AnyRef] ⇒
        val metadata = array(index)
        array(index) = null
        metadata
      case _ ⇒ null
    }
  }

}
