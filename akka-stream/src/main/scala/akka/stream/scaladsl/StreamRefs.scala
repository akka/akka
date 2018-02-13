/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.annotation.ApiMayChange
import akka.stream.{ SinkRef, SourceRef, StreamRefAttributes }
import akka.stream.impl.streamref.{ SinkRefStageImpl, SourceRefStageImpl }
import akka.util.OptionVal

import scala.concurrent.Future

/**
 * API MAY CHANGE: The functionality of stream refs is working, however it is expected that the materialized value
 * will eventually be able to remove the Future wrapping the stream references. For this reason the API is now marked
 * as API may change. See ticket https://github.com/akka/akka/issues/24372 for more details.
 *
 * Factories for creating stream refs.
 */
@ApiMayChange
object StreamRefs {

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Adheres to [[StreamRefAttributes]].
   *
   * See more detailed documentation on [[SourceRef]].
   */
  @ApiMayChange
  def sourceRef[T](): Sink[T, Future[SourceRef[T]]] =
    Sink.fromGraph(new SinkRefStageImpl[T](OptionVal.None))

  /**
   * A local [[Sink]] which materializes a [[SourceRef]] which can be used by other streams (including remote ones),
   * to consume data from this local stream, as if they were attached in the spot of the local Sink directly.
   *
   * Adheres to [[StreamRefAttributes]].
   *
   * See more detailed documentation on [[SinkRef]].
   */
  @ApiMayChange
  def sinkRef[T](): Source[T, Future[SinkRef[T]]] =
    Source.fromGraph(new SourceRefStageImpl[T](OptionVal.None))
}
