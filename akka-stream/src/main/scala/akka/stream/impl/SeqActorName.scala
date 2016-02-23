/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
abstract class SeqActorName {
  def next(): String
  def copy(name: String): SeqActorName
}
object SeqActorName {
  def apply(prefix: String) = new SeqActorNameImpl(prefix, new AtomicLong(0))
}

private[akka] final class SeqActorNameImpl(val prefix: String, counter: AtomicLong) extends SeqActorName {
  def next(): String = prefix + '-' + counter.getAndIncrement()

  def copy(newPrefix: String): SeqActorName = new SeqActorNameImpl(newPrefix, counter)
}
