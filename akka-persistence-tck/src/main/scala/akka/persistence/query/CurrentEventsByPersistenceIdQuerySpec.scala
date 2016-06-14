package akka.persistence.query

import scala.concurrent.duration._

trait CurrentEventsByPersistenceIdQuerySpec { _: QuerySpec ⇒
  "CurrentEventsByPersistenceIdQuery" must {
    "find existing events" in {
      persistEventsFor(1, 3, "a")
      withCurrentEventsByPersistenceId()("a", 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, "a", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "a", 2, "a-2"))
        tp.expectNoMsg(100.millis)
        tp.request(2)
        tp.expectNext(EventEnvelope(3, "a", 3, "a-3"))
        tp.expectComplete()
      }
    }

    "find existing events up to a sequence number" in {
      persistEventsFor(1, 3, "b")
      withCurrentEventsByPersistenceId()("b", 0L, 2L) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1, "b", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "b", 2, "a-2"))
        tp.expectComplete()
      }
    }

    "find existing events from an offset" in {
      persistEventsFor(1, 3, "c")

      withCurrentEventsByPersistenceId()("c", 0L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 1L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 1L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 2L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 2L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 3L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 3L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 4L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 0L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()("c", 1L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, "c", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "c", 2, "a-2"))
        tp.expectNext(EventEnvelope(3, "c", 3, "a-3"))
        tp.expectComplete()
      }
    }

    "not see new events after demand request" in {
      persistEventsFor(1, 3, "f")
      withCurrentEventsByPersistenceId()("f", 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, "f", 1, "a-1"))
        tp.expectNext(EventEnvelope(2, "f", 2, "a-2"))
        tp.expectNoMsg(100.millis)

        persistEventsFor(4, 5, "f")

        tp.expectNoMsg(100.millis)
        tp.request(5)
        tp.expectNext(EventEnvelope(3, "f", 3, "a-3"))
        tp.expectComplete() // event 4 not seen
      }
    }

    "return empty stream for cleaned journal from 0 to MaxLong" in {
      persistEventsFor(1, 3, "g1")
      deleteMessages("g1", Long.MaxValue)
      withCurrentEventsByPersistenceId()("g1", 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for cleaned journal from 0 to 0" in {
      persistEventsFor(1, 3, "g2")
      deleteMessages("g2", Long.MaxValue)
      withCurrentEventsByPersistenceId()("g1", 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return remaining values after partial journal cleanup" in {
      persistEventsFor(1, 3, "h")
      deleteMessages("h", 2L)
      withCurrentEventsByPersistenceId()("h", 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectNext(EventEnvelope(3, "h", 3, "a-3"))
        tp.expectComplete()
      }
    }

    "return empty stream for empty journal" in {
      withCurrentEventsByPersistenceId()("i", 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for journal from 0 to 0" in {
      persistEventsFor(1, 3, "k1")
      withCurrentEventsByPersistenceId()("k1", 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for empty journal from 0 to 0" in {
      withCurrentEventsByPersistenceId()("k2", 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for journal from seqNo greater than highestSeqNo" in {
      persistEventsFor(1, 3, "k1")
      withCurrentEventsByPersistenceId()("l", 4L, 3L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }
  }
}
