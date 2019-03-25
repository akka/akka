/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl

import java.util.concurrent.CompletionStage

import akka.annotation.ApiMayChange
import akka.stream._

/**
 * API MAY CHANGE: The functionality of stream refs is working, however it is expected that the materialized value
 * will eventually be able to remove the Future wrapping the stream references. For this reason the API is now marked
 * as API may change. See ticket https://github.com/akka/akka/issues/24372 for more details.
 *
 * Factories for creating stream refs.
 */
@ApiMayChange
object StreamRefs {
  import scala.compat.java8.FutureConverters._

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Adheres to [[StreamRefAttributes]].
   *
   * See more detailed documentation on [[SourceRef]].
   */
  @ApiMayChange
  def sourceRef[T](): javadsl.Sink[T, CompletionStage[SourceRef[T]]] =
    scaladsl.StreamRefs.sourceRef[T]().mapMaterializedValue(_.toJava).asJava

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Adheres to [[StreamRefAttributes]].
   *
   * See more detailed documentation on [[SinkRef]].
   */
  @ApiMayChange
  def sinkRef[T](): javadsl.Source[T, CompletionStage[SinkRef[T]]] =
    scaladsl.StreamRefs.sinkRef[T]().mapMaterializedValue(_.toJava).asJava

}
