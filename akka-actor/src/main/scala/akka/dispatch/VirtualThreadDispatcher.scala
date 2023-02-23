/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.dispatch

import com.typesafe.config.Config

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

final class VirtualThreadConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites) {
  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    println(s"Ignoring thread factory: $threadFactory")
    // Note: we ignore the threadFactory, a specific one is needed for virtual threads
    new VirtualThreadExecutorFactory(id)
  }

  final class VirtualThreadExecutorFactory(id: String) extends ExecutorServiceFactory {

    override def createExecutorService: ExecutorService = {
      val factory =
        Thread.ofVirtual().name(id + "-", 0L).uncaughtExceptionHandler(MonitorableThreadFactory.doNothing).factory()

      Executors.newThreadPerTaskExecutor(factory)
    }
  }

}
