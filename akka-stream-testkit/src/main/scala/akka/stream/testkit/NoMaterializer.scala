/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import akka.stream.ActorMaterializerSettings
import akka.stream.Attributes
import akka.stream.ClosedShape
import akka.stream.Graph
import akka.stream.MaterializationContext
import akka.stream.Materializer

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

/**
 * A dummy materializer implementation that doesn't do anything, if you try to use it in any way it will throw
 * exceptions.
 */
object NoMaterializer extends Materializer {
  override def withNamePrefix(name: String): Materializer =
    throw new UnsupportedOperationException("NoMaterializer cannot be named")
  override def materialize[Mat](runnable: Graph[ClosedShape, Mat]): Mat =
    throw new UnsupportedOperationException("NoMaterializer cannot materialize")
  override def materialize[Mat](runnable: Graph[ClosedShape, Mat], defaultAttributes: Attributes): Mat =
    throw new UnsupportedOperationException("NoMaterializer cannot materialize")

  override def executionContext: ExecutionContextExecutor =
    throw new UnsupportedOperationException("NoMaterializer does not provide an ExecutionContext")

  def scheduleOnce(delay: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a single event")

  def schedulePeriodically(initialDelay: FiniteDuration, interval: FiniteDuration, task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot schedule a repeated event")

  override def scheduleWithFixedDelay(
      initialDelay: FiniteDuration,
      delay: FiniteDuration,
      task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot scheduleWithFixedDelay")

  override def scheduleAtFixedRate(
      initialDelay: FiniteDuration,
      interval: FiniteDuration,
      task: Runnable): Cancellable =
    throw new UnsupportedOperationException("NoMaterializer cannot scheduleAtFixedRate")

  override def shutdown(): Unit = throw new UnsupportedOperationException("NoMaterializer cannot shutdown")

  override def isShutdown: Boolean = throw new UnsupportedOperationException("NoMaterializer cannot shutdown")

  override def system: ActorSystem =
    throw new UnsupportedOperationException("NoMaterializer does not have an actorsystem")

  override private[akka] def logger = throw new UnsupportedOperationException("NoMaterializer does not have a logger")

  override private[akka] def supervisor =
    throw new UnsupportedOperationException("NoMaterializer does not have a supervisor")

  override private[akka] def actorOf(context: MaterializationContext, props: Props) =
    throw new UnsupportedOperationException("NoMaterializer cannot spawn actors")

  override def settings: ActorMaterializerSettings =
    throw new UnsupportedOperationException("NoMaterializer does not have settings")
}
