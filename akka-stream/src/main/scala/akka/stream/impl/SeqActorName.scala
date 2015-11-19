/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.impl

import java.util.concurrent.atomic.AtomicLong

/**
 * INTERNAL API
 * As discussed in https://github.com/akka/akka/issues/16613
 *
 * Generator of sequentially numbered actor names.
 * Pulled out from HTTP internals, most often used used by streams which materialize actors directly
 */
private[akka] final class SeqActorName(prefix: String) extends AtomicLong {
  def next(): String = prefix + '-' + getAndIncrement()
}
