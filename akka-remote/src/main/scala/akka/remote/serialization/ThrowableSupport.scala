/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.serialization

import akka.actor.ExtendedActorSystem
import akka.remote.ContainerFormats

/**
 * INTERNAL API
 */
private[akka] class ThrowableSupport(system: ExtendedActorSystem) {

  private val payloadSupport = new WrappedPayloadSupport(system)

  def serializeThrowable(t: Throwable): Array[Byte] = {
    val b = ContainerFormats.Throwable.newBuilder()
      .setClassName(t.getClass.getName)
    if (t.getMessage != null)
      b.setMessage(t.getMessage)
    if (t.getCause != null)
      b.setCause(payloadSupport.payloadBuilder(t.getCause))
    val stackTrace = t.getStackTrace
    if (stackTrace != null) {
      var i = 0
      while (i < stackTrace.length) {
        b.addStackTrace(stackTraceElementBuilder(stackTrace(i)))
        i += 1
      }
    }

    b.build().toByteArray
  }

  def stackTraceElementBuilder(elem: StackTraceElement): ContainerFormats.StackTraceElement.Builder = {
    ContainerFormats.StackTraceElement.newBuilder()
      .setClassName(elem.getClassName)
      .setMethodName(elem.getMethodName)
      .setFileName(elem.getFileName)
      .setLineNumber(elem.getLineNumber)
  }

  def deserializeThrowable(bytes: Array[Byte]): Throwable = {
    val protoT = ContainerFormats.Throwable.parseFrom(bytes)
    val t: Throwable =
      if (protoT.hasCause) {
        val cause = payloadSupport.deserializePayload(protoT.getCause).asInstanceOf[Throwable]
        system.dynamicAccess.createInstanceFor[Throwable](
          protoT.getClassName,
          List(classOf[String] → protoT.getMessage, classOf[Throwable] → cause)).get
      } else
        system.dynamicAccess.createInstanceFor[Throwable](
          protoT.getClassName,
          List(classOf[String] → protoT.getMessage)).get

    import scala.collection.JavaConverters._
    val stackTrace =
      (protoT.getStackTraceList.asScala.map { elem ⇒
        new StackTraceElement(elem.getClassName, elem.getMethodName, elem.getFileName, elem.getLineNumber)
      }).toArray
    t.setStackTrace(stackTrace)
    t
  }

}
