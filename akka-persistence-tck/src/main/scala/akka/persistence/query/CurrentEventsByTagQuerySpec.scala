package akka.persistence.query

import scala.concurrent.duration._

trait CurrentEventsByTagQuerySpec extends {
  _: QuerySpec ⇒

  "CurrentEventsByTagQuery" must {

    "not produce anything given an empty tag" in {
      withCurrentEventsByTag()(tag = "", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectComplete()
      }
    }

    "not produce anything if there aren't any events matching the tag" in {
      withCurrentEventsByTag()(tag = "unknown-tag", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectComplete()
      }
    }

    "find existing events" in {
      val pidA = pid
      val pidB = nextPid

      val tagA = tag
      val tagB = nextTag

      persist(1, 1, pidB)
      persist(2, 2, pidB, tagA)
      persist(1, 1, pidA, tagB)
      persist(3, 3, pidB, tagA)
      persist(2, 2, pidA, tagA)

      withCurrentEventsByTag()(tag = tagA, offset = 0L) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(2L, pidB, 3L, "a-3"))
        tp.expectNoMsg(100.millis)
        tp.request(2)
        tp.expectNext(EventEnvelope(3L, pidA, 2L, "a-2"))
        tp.expectComplete()
      }

      withCurrentEventsByTag()(tag = tagB, offset = 0L) { tp ⇒
        tp.request(5)
        tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
        tp.expectComplete()
      }
    }

    "not see new events after demand request" in {
      val pidA = pid
      val pidB = nextPid
      val pidC = nextPid

      persist(1, 1, pidA, tag)
      persist(1, 1, pidB, tag)
      persist(2, 2, pidA, tag)

      withCurrentEventsByTag()(tag, offset = 0L) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
        tp.expectNext(EventEnvelope(2L, pidB, 1L, "a-1"))
        tp.expectNoMsg(100.millis)

        persist(1, 1, pidC, tag)

        tp.expectNoMsg(100.millis)
        tp.request(5)
        tp.expectNext(EventEnvelope(3L, pidA, 2L, "a-2"))
        tp.expectComplete()
      }
    }

    "find events from offset" in {
      val pidA = pid
      val pidB = nextPid
      val pidC = nextPid

      persist(1, 1, pidA, pid)
      persist(1, 2, pidB, pid)
      persist(2, 2, pidA, pid)

      withCurrentEventsByTag()(tag = pid, offset = 2L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(2L, pidB, 1L, "a-1"))
        tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(4L, pidA, 2L, "a-2"))
        tp.expectComplete()
      }
    }

    "find event tagged with multiple tags" in {
      val tagA = tag
      val tagB = nextTag
      val tagC = nextTag

      persist(1, 1, pid, tagA, tagB, tagC)
      withCurrentEventsByTag()(tag = tagA, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByTag()(tag = tagB, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectComplete()
      }

      withCurrentEventsByTag()(tag = tagC, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectComplete()
      }
    }

    "find multiple events tagged with multiple tags" in {
      val tagA = tag
      val tagB = nextTag
      val tagC = nextTag

      val pidA = pid
      val pidB = nextPid
      val pidC = nextPid

      persist(1, 1, pidA, tagA)
      persist(2, 2, pidA, tagA, tagB)
      persist(3, 3, pidA, tagA, tagB, tagC)
      persist(1, 1, pidB, tagA)
      persist(2, 2, pidB, tagA, tagB)
      persist(3, 3, pidB, tagA, tagB, tagC)
      persist(1, 1, pidC, tagA)
      persist(2, 2, pidC, tagA, tagB)
      persist(3, 3, pidC, tagA, tagB, tagC)

      withCurrentEventsByTag()(tag = tagA, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
        tp.expectNext(EventEnvelope(2L, pidA, 2L, "a-2"))
        tp.expectNext(EventEnvelope(3L, pidA, 3L, "a-3"))
        tp.expectNext(EventEnvelope(4L, pidB, 1L, "a-1"))
        tp.expectNext(EventEnvelope(5L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(6L, pidB, 3L, "a-3"))
        tp.expectNext(EventEnvelope(7L, pidC, 1L, "a-1"))
        tp.expectNext(EventEnvelope(8L, pidC, 2L, "a-2"))
        tp.expectNext(EventEnvelope(9L, pidC, 3L, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByTag()(tag = tagB, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pidA, 2L, "a-2"))
        tp.expectNext(EventEnvelope(2L, pidA, 3L, "a-3"))
        tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(4L, pidB, 3L, "a-3"))
        tp.expectNext(EventEnvelope(5L, pidC, 2L, "a-2"))
        tp.expectNext(EventEnvelope(6L, pidC, 3L, "a-3"))
        tp.expectComplete()
      }

      withCurrentEventsByTag()(tag = tagC, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pidA, 3L, "a-3"))
        tp.expectNext(EventEnvelope(2L, pidB, 3L, "a-3"))
        tp.expectNext(EventEnvelope(3L, pidC, 3L, "a-3"))
        tp.expectComplete()
      }
    }

    "not find events by search tag formatted as csv" in {
      val pidA = pid
      val pidB = nextPid
      val pidC = nextPid

      val tagA = tag
      val tagB = nextTag

      persist(1, 1, pidA, tagA)
      persist(1, 1, pidB, tagB)

      List(";", ",", "").foreach { separatorChar ⇒
        withCurrentEventsByTag()(tag = s"$tagA$separatorChar$tagB", offset = 0L) { tp ⇒
          tp.request(10)
          tp.expectComplete()
        }
      }

      // only when stored as csv
      persist(1, 1, pidC, s"$tagA,$tagB")

      withCurrentEventsByTag()(tag = s"$tagA,$tagB", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pidC, 1L, "a-1"))
        tp.expectComplete()
      }
    }
  }
}