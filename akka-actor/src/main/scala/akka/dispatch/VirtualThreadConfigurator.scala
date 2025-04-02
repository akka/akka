/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import akka.actor.DynamicAccess
import akka.annotation.InternalApi
import com.typesafe.config.Config

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object VirtualThreadConfigurator {

  // Note: since we still support JDK 11 and 17, we need to access the factory method reflectively
  private def virtualThreadFactory(dynamicAccess: DynamicAccess, virtualThreadName: String): ThreadFactory = {
    val ofVirtualMethod =
      try {
        classOf[Thread].getMethod("ofVirtual")
      } catch {
        case _: NoSuchMethodError =>
          throw new IllegalStateException("Virtual thread executors only supported on JDK 21 and newer")
      }
    val ofVirtual = ofVirtualMethod.invoke(null)
    // java.lang.ThreadBuilders.VirtualThreadBuilder is package private
    val ofVirtualInterface = dynamicAccess.getClassFor[AnyRef]("java.lang.Thread$Builder$OfVirtual").get
    val ofVirtualWithName =
      if (virtualThreadName.nonEmpty) {
        val nameMethod = ofVirtualInterface.getMethod("name", classOf[String])
        nameMethod.invoke(ofVirtual, virtualThreadName)
      } else ofVirtual
    val factoryMethod = ofVirtualInterface.getMethod("factory")
    factoryMethod.invoke(ofVirtualWithName).asInstanceOf[ThreadFactory]
  }

  private def threadPerTaskExecutor(threadFactory: ThreadFactory): ExecutorService = {
    val newThreadPerTaskMethod = classOf[Executors].getMethod("newThreadPerTaskExecutor", classOf[ThreadFactory])
    newThreadPerTaskMethod.invoke(null, threadFactory).asInstanceOf[ExecutorService]
  }

  private class VirtualThreadExecutorServiceFactory(tf: ThreadFactory) extends ExecutorServiceFactory {
    override def createExecutorService: ExecutorService = threadPerTaskExecutor(tf)
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class VirtualThreadConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {
  import VirtualThreadConfigurator._

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    val tf = threadFactory match {
      case m: MonitorableThreadFactory =>
        // add the dispatcher id to the thread names
        virtualThreadFactory(prerequisites.dynamicAccess, m.name + "-" + id)
      case _ => virtualThreadFactory(prerequisites.dynamicAccess, "-" + id)
    }
    new VirtualThreadExecutorServiceFactory(tf)
  }

}
