package akka.persistence.query

import scala.concurrent.duration._

trait CurrentEventsByPersistenceIdQuerySpec { _: QuerySpec ⇒
  "CurrentEventsByPersistenceIdQuery" must {
    "find existing events" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(persistenceId = pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNoMsg(100.millis)
        tp.request(2)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }
    }

    "find existing events up to a sequence number" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(pid, 0L, 2L) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectComplete()
      }
    }

    "find existing events from an offset" in {
      val pid = persist(1, 3, nextPid)

      withCurrentEventsByPersistenceId()(pid, 0L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 1L, 1L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 1L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 2L, 2L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 2L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 3L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 3L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 4L, 4L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 0L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByPersistenceId()(pid, 1L, 3L) { tp ⇒
        tp.request(Long.MaxValue)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }
    }

    "not see new events after demand request" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1, pid, 1, "a-1"))
        tp.expectNext(EventEnvelope(2, pid, 2, "a-2"))
        tp.expectNoMsg(100.millis)

        persist(4, 5, pid)

        tp.expectNoMsg(100.millis)
        tp.request(5)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete() // event 4 not seen
      }
    }

    "return empty stream for cleaned journal from 0 to MaxLong" in {
      val pid = persist(1, 3, nextPid)
      delete(pid)
      withCurrentEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for cleaned journal from 0 to 0" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(pid, 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return remaining values after partial journal cleanup" in {
      val pid = persist(1, 3, nextPid)
      delete(pid, 2L)
      withCurrentEventsByPersistenceId()(pid, 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectNext(EventEnvelope(3, pid, 3, "a-3"))
        tp.expectComplete()
      }
    }

    "return empty stream for empty journal" in {
      withCurrentEventsByPersistenceId()(nextPid, 0L, Long.MaxValue) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for journal from 0 to 0" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(pid, 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for empty journal from 0 to 0" in {
      withCurrentEventsByPersistenceId()(nextPid, 0L, 0L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }

    "return empty stream for journal from seqNo greater than highestSeqNo" in {
      val pid = persist(1, 3, nextPid)
      withCurrentEventsByPersistenceId()(pid, 4L, 3L) { tp ⇒
        tp.request(1)
        tp.expectComplete()
      }
    }
  }
}
