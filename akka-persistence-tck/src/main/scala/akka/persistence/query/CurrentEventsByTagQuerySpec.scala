package akka.persistence.query

import akka.persistence.CurrentEventsByTagCapabilityFlags
import akka.persistence.scalatest.{ MayVerb, OptionalTests }

import scala.concurrent.duration._

trait CurrentEventsByTagQuerySpec extends OptionalTests with CurrentEventsByTagCapabilityFlags {
  _: QuerySpec ⇒

  "CurrentEventsByTagQuery" must {
    optional(flag = supportsOrderingByDateIndependentlyOfPersistenceId) {
      "find existing events" in {
        val pidA = nextPid
        val pidB = nextPid

        persist(1, 1, pidB)
        persist(2, 2, pidB, "green")
        persist(1, 1, pidA, "black")
        persist(3, 3, pidB, "green")
        persist(2, 2, pidA, "green")

        withCurrentEventsByTag()(tag = "green", offset = 0L) { tp ⇒
          tp.request(2)
          tp.expectNext(EventEnvelope(1L, pidB, 2L, "a-2"))
          tp.expectNext(EventEnvelope(2L, pidB, 3L, "a-3"))
          tp.expectNoMsg(100.millis)
          tp.request(2)
          tp.expectNext(EventEnvelope(3L, pidA, 2L, "a-2"))
          tp.expectComplete()
        }

        withCurrentEventsByTag()(tag = "black", offset = 0L) { tp ⇒
          tp.request(5)
          tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
          tp.expectComplete()
        }
      }

      "not see new events after demand request" in {
        val pidA = nextPid
        val pidB = nextPid
        val pidC = nextPid

        persist(1, 1, pidA, "red")
        persist(1, 1, pidB, "red")
        persist(2, 2, pidA, "red")

        withCurrentEventsByTag()(tag = "red", offset = 0L) { tp ⇒
          tp.request(2)
          tp.expectNext(EventEnvelope(1L, pidA, 1L, "a-1"))
          tp.expectNext(EventEnvelope(2L, pidB, 1L, "a-1"))
          tp.expectNoMsg(100.millis)

          persist(1, 1, pidC, "red")

          tp.expectNoMsg(100.millis)
          tp.request(5)
          tp.expectNext(EventEnvelope(3L, pidA, 2L, "a-2"))
          tp.expectComplete()
        }
      }

      "find events from offset" in {
        persist(1, 1, "f", "yellow")
        persist(1, 1, "g", "purple")
        persist(2, 2, "h", "yellow")

      }
    }

    optional(flag = supportsOrderingByPersistenceIdAndSequenceNr) {
      "find existing events" in {

      }

      "not see new events after demand request" in {

      }

      "find events from offset" in {

      }
    }
  }

}
