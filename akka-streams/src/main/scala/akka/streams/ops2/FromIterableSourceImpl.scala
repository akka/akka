package akka.streams.ops2

import scala.annotation.tailrec

object FromIterableSourceImpl {
  def apply[O](downstream: Downstream[O], subscribable: Subscribable, iterable: Iterable[O]): SyncSource =
    new SyncSource {
      val it = iterable.iterator
      var alreadyCompleted = false

      def handleRequestMore(n: Int): Effect = requestMore(n)

      def requestMore(n: Int): Effect =
        if (n > 0)
          if (it.hasNext)
            if (n == 1) downstream.next(it.next()) ~ maybeCompleted()
            else rec(n) ~ maybeCompleted()
          else maybeCompleted()
        else throw new IllegalStateException(s"n = $n is not > 0")

      def maybeCompleted() = if (it.hasNext || alreadyCompleted) Continue else {
        alreadyCompleted = true
        downstream.complete
      }

      @tailrec def rec(remaining: Int, result: Effect = Continue): Effect =
        if (remaining > 0 && it.hasNext) rec(remaining - 1, result ~ downstream.next(it.next()))
        else result

      def handleCancel(): Effect = ???
    }
}
