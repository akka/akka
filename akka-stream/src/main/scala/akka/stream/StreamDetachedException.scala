/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import scala.util.control.NoStackTrace

/**
 * This exception signals that materialized value is already detached from stream. This usually happens
 * when stream is completed and an ActorSystem is shut down while materialized object is still available.
 */
final class StreamDetachedException
  extends RuntimeException("Stream is terminated. Materialized value is detached.")
  with NoStackTrace
