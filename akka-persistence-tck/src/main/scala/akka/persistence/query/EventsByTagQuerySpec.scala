package akka.persistence.query

import scala.concurrent.duration._

trait EventsByTagQuerySpec {
  _: QuerySpec ⇒
  "EventsByTagQuery" must {
    "find new events" in {
      val pidA = pid
      val pidB = nextPid

      persist(1, 1, pidA, "purple")
      withEventsByTag()(tag = "purple", offset = 0L) { tp ⇒
        tp.request(2)
        tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
        tp.expectNoMsg(100.millis)

        persist(1, 2, pidB, "purple")

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
      persist(2, 2, pidA, "pink")
      persist(3, 3, pidA, "pink")
      persist(1, 1, pidB)
      persist(2, 2, pidB, "pink")
      persist(1, 1, pidC, "pink")

      withEventsByTag()(tag = "pink", offset = 2L) { tp ⇒
        tp.request(10)
        tp.expectNext(EventEnvelope(2L, pidA, 3L, "a-3"))
        tp.expectNext(EventEnvelope(3L, pidB, 2L, "a-2"))
        tp.expectNext(EventEnvelope(4L, pidC, 1L, "a-1"))
        tp.expectNoMsg(100.millis)
        tp.cancel()
      }
    }
  }
}
