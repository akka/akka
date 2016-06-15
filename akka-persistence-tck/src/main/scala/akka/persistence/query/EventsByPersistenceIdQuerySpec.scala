package akka.persistence.query

import scala.concurrent.duration._

trait EventsByPersistenceIdQuerySpec { _: QuerySpec ⇒
  "EventsByPersistenceIdQuery" must {
    "find new events" in {
      val pid = nextPid

      withEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(5)
        tp.expectNoMsg(100.millis)

        persist(1, 3, pid)

        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)

        persist(4, 4, pid)
        tp.expectNext(EventEnvelope(4, pid, 4, "a-4"))
        tp.cancel()
      }
    }

    "find new events up to a sequence number" in {
      val pid = persist(1, 3, nextPid)

      withEventsByPersistenceId()(pid, 0L, 4L) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)

        persist(4, 4, pid)

        tp.expectNext(EventEnvelope(4, pid, 4, "a-4"))
        tp.expectComplete()
      }
    }

    "find new events after demand request" in {
      val pid = persist(1, 3, nextPid)

      withEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNoMsg(100.millis)

        persist(4, 4, pid)

        tp.request(5)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNext(EventEnvelope(4, pid, 4, "a-4"))
        tp.expectNoMsg(100.millis)

        tp.cancel()
      }
    }
  }
}
