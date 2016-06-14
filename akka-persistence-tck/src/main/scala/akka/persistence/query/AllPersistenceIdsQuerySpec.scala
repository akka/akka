package akka.persistence.query

import scala.concurrent.duration._

trait AllPersistenceIdsQuerySpec { _: QuerySpec ⇒
  "AllPersistenceIdsQuery" must {
    "find new persistenceIds" in {
      withAllPersistenceIdsQuery() { tp ⇒
        val persistenceIds = List("pid-1", "pid-2", "pid-3")
        persistenceIds.foreach(persistEventFor)
        tp.request(3)
        tp.expectNextUnorderedN(persistenceIds)
        tp.expectNoMsg(100.millis)

        persistEventFor("pid-4")
        tp.request(1)
        tp.expectNext("pid-4")
        tp.expectNoMsg(100.millis)

        persistEventFor("pid-5")
        tp.request(1)
        tp.expectNext("pid-5")
        tp.expectNoMsg(100.millis)

        persistEventFor("pid-6")
        tp.request(1)
        tp.expectNext("pid-6")
        tp.expectNoMsg(100.millis)

        val morePids = (1 to 100).map("f" + _)
        morePids.foreach(persistEventFor)
        tp.request(100)
        tp.expectNextUnorderedN(morePids)
        tp.expectNoMsg(100.millis)

        tp.cancel()
        tp.expectNoMsg(100.millis)

        (1 to 6).map("pid-" + _).foreach(deleteMessages(_))
      }
    }
  }
}
