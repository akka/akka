package akka.persistence.query

import scala.concurrent.duration._

trait EventsByTagQuerySpec { _: QuerySpec ⇒

  "EventsByTagQuery" must {
    "not produce anything given an empty tag" in {
      withEventsByTag()(tag = "", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "not produce anything if there aren't any events matching the tag" in {
      withEventsByTag()(tag = "unknown-tag", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find current events" in {
      persist(1, 2, pid, tag)

      withEventsByTag()(tag, offset = 0L) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectNext(EventEnvelope(2L, pid, 2L, "a-2"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find new events" in {
      val pidA = pid
      val pidB = nextPid

      persist(1, 1, pidA, tag)

      withEventsByTag()(tag, offset = 0L) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
        tp.expectNoMsg(100.millis)

        persist(1, 2, pidB, tag)

        tp.expectNext(EventEnvelope(2L, pidB, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.request(10)
        tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find events from offset" in {
      val pidA = pid
      val pidB = nextPid
      val pidC = nextPid

      persist(1, 1, pidA)
      persist(2, 2, pidA, tag)
      persist(3, 3, pidA, tag)
      persist(1, 1, pidB)
      persist(2, 2, pidB, tag)
      persist(1, 1, pidC, tag)

      withEventsByTag()(tag, offset = 2L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(2L, pidA, 3L, "a-3"))
        tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(4L, pidC, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    "find event tagged with multiple tags" in {
      val tagA = tag
      val tagB = nextTag
      val tagC = nextTag

      persist(1, 1, pid, tagA, tagB, tagC)
      withEventsByTag()(tag = tagA, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByTag()(tag = tagB, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }

      withEventsByTag()(tag = tagC, offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(1L, pid, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
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

    withEventsByTag()(tag = tagA, offset = 0L) { tp ⇒
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
      tp.expectNoMsg(100.millis)
      tp.cancel()
    }

    withEventsByTag()(tag = tagB, offset = 0L) { tp ⇒
      tp.request(10)
      tp.expectNext(EventEnvelope(1L, pidA, 2L, "a-2"))
      tp.expectNext(EventEnvelope(2L, pidA, 3L, "a-3"))
      tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
      tp.expectNext(EventEnvelope(4L, pidB, 3L, "a-3"))
      tp.expectNext(EventEnvelope(5L, pidC, 2L, "a-2"))
      tp.expectNext(EventEnvelope(6L, pidC, 3L, "a-3"))
      tp.expectNoMsg(100.millis)
      tp.cancel()
    }

    withEventsByTag()(tag = tagC, offset = 0L) { tp ⇒
      tp.request(10)
      tp.expectNext(EventEnvelope(1L, pidA, 3L, "a-3"))
      tp.expectNext(EventEnvelope(2L, pidB, 3L, "a-3"))
      tp.expectNext(EventEnvelope(3L, pidC, 3L, "a-3"))
      tp.expectNoMsg(100.millis)
      tp.cancel()
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
      withEventsByTag()(tag = s"$tagA$separatorChar$tagB", offset = 0L) { tp ⇒
        tp.request(10)
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }

    // only when stored as csv format
    withEventsByTag()(tag = s"$tagA,$tagB", offset = 0L) { tp ⇒
      tp.request(10)
      tp.expectNoMsg(100.millis)

      persist(1, 1, pidC, s"$tagA,$tagB")

      tp.expectNext(EventEnvelope(1L, pidC, 1L, "a-1"))
      tp.expectNoMsg(100.millis)
      tp.cancel()
    }
  }
}
