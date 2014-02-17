package akka.streams.impl.ops

import scala.annotation.tailrec
import akka.streams.impl._

class FromIterableSourceImpl[O](downstream: Downstream[O], ctx: ContextEffects, iterable: Iterable[O]) extends SyncSource {
  val it = iterable.iterator
  var alreadyCompleted = false

  def handleRequestMore(n: Int): Effect = requestMore(n)

  def requestMore(n: Int): Effect = {
    @tailrec def rec(remaining: Int, result: Effect = Continue): Effect =
      if (remaining > 0 && it.hasNext) rec(remaining - 1, result ~ downstream.next(it.next()))
      else result

    if (n > 0)
      if (it.hasNext)
        if (n == 1) downstream.next(it.next()) ~ maybeCompleted()
        else rec(n) ~ maybeCompleted()
      else maybeCompleted()
    else throw new IllegalStateException(s"n = $n is not > 0")
  }

  def maybeCompleted() = if (it.hasNext || alreadyCompleted) Continue else {
    alreadyCompleted = true
    downstream.complete
  }

  def handleCancel(): Effect = ???
}
