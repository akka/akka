/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.ExecutionContextExecutorService
import java.util.concurrent.Executors
import akka.event.LoggingAdapter

class DispatchersImpl(settings: akka.actor.ActorSystem.Settings, log: LoggingAdapter) extends Dispatchers {
  private val ex: ExecutionContextExecutorService = new ExecutionContextExecutorService {
    val es = Executors.newWorkStealingPool()

    def reportFailure(cause: Throwable): Unit = log.error(cause, "exception caught by default executor")
    def execute(command: Runnable): Unit = es.execute(command)

    def awaitTermination(x$1: Long, x$2: java.util.concurrent.TimeUnit): Boolean = es.awaitTermination(x$1, x$2)
    def invokeAll[T](x$1: java.util.Collection[_ <: java.util.concurrent.Callable[T]], x$2: Long, x$3: java.util.concurrent.TimeUnit): java.util.List[java.util.concurrent.Future[T]] = es.invokeAll(x$1, x$2, x$3)
    def invokeAll[T](x$1: java.util.Collection[_ <: java.util.concurrent.Callable[T]]): java.util.List[java.util.concurrent.Future[T]] = es.invokeAll(x$1)
    def invokeAny[T](x$1: java.util.Collection[_ <: java.util.concurrent.Callable[T]], x$2: Long, x$3: java.util.concurrent.TimeUnit): T = es.invokeAny(x$1, x$2, x$3)
    def invokeAny[T](x$1: java.util.Collection[_ <: java.util.concurrent.Callable[T]]): T = es.invokeAny(x$1)
    def isShutdown(): Boolean = es.isShutdown()
    def isTerminated(): Boolean = es.isTerminated()
    def shutdown(): Unit = es.shutdown()
    def shutdownNow(): java.util.List[Runnable] = es.shutdownNow()
    def submit(x$1: Runnable): java.util.concurrent.Future[_] = es.submit(x$1)
    def submit[T](x$1: Runnable, x$2: T): java.util.concurrent.Future[T] = es.submit(x$1, x$2)
    def submit[T](x$1: java.util.concurrent.Callable[T]): java.util.concurrent.Future[T] = es.submit(x$1)
  }
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor = ex //FIXME respect selection
  def shutdown(): Unit = {
    ex.shutdown()
  }
}
