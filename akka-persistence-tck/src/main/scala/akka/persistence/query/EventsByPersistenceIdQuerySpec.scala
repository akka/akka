package akka.persistence.query

import scala.concurrent.duration._

trait EventsByPersistenceIdQuerySpec { _: QuerySpec ⇒
  "EventsByPersistenceIdQuery" must {
    "find new events" in {
      withEventsByPersistenceId()("a", 0L, Long.MaxValue) { tp ⇒
        tp.request(5)
        tp.expectNoMsg(100.millis)

        persistEventsFor(1, 3, "a")

        tp.expectNext(EventEnvelope(1, "a", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "a", 2, "a-2"))
        tp.expectNext(EventEnvelope(3, "a", 3, "a-3"))
        tp.expectNoMsg(100.millis)

        persistEventsFor(4, 4, "a")
        tp.expectNext(EventEnvelope(4, "a", 4, "a-4"))
        tp.cancel()
      }
      deleteMessages("a")
    }

    "find new events up to a sequence number" in {
      persistEventsFor(1, 3, "b")

      withEventsByPersistenceId()("b", 0L, 4L) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1, "b", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "b", 2, "a-2"))
        tp.expectNext(EventEnvelope(3, "b", 3, "a-3"))
        tp.expectNoMsg(100.millis)

        persistEventsFor(4, 4, "b")

        tp.expectNext(EventEnvelope(4, "b", 4, "a-4"))
        tp.expectComplete()
      }
      deleteMessages("b")
    }

    "find new events after demand request" in {
      persistEventsFor(1, 3, "c")

      withEventsByPersistenceId()("c", 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectNoMsg(100.millis)

        persistEventsFor(4, 4, "c")

        tp.request(5)
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectNext(EventEnvelope(4, "c", 4, "a-4"))
        tp.expectNoMsg(100.millis)

        tp.cancel()
      }
      deleteMessages("c")
    }
  }
}
