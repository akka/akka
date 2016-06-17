package akka.persistence.query

import scala.concurrent.duration._

trait EventsByPersistenceIdQuerySpec { _: QuerySpec ⇒
  "EventsByPersistenceIdQuery" must {

    "not produce anything if there aren't any events" in {
      withEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(10)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find existing events" in {
      persist(1, 3, pid)

      withEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByPersistenceId()(pid, 1L, Long.MaxValue) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find existing events from an offset" in {
      persist(1, 3, pid)

      withEventsByPersistenceId()(pid, 0L, 0L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByPersistenceId()(pid, 0L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 1L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 1L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 2L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 2L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 3L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 3L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByPersistenceId()(pid, 4L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByPersistenceId()(pid, 0L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withEventsByPersistenceId()(pid, 1L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }
    }

    "find new events" in {
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

    "find new events if the stream starts after current latest event" in {
      persist(1, 3, pid)

      withEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectNoMsg(100.millis)

        persist(4, 4, pid)

        tp.expectNext(EventEnvelope(4, pid, 4, "a-4"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find new events up to a sequence number" in {
      persist(1, 3, pid)

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
      persist(1, 3, pid)

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
