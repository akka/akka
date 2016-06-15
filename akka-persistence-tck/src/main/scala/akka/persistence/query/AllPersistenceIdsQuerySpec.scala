package akka.persistence.query

import scala.concurrent.duration._

trait AllPersistenceIdsQuerySpec { _: QuerySpec ⇒
  "AllPersistenceIdsQuery" must {
    "find new persistenceIds" in {
      withAllPersistenceIdsQuery() { tp ⇒
        val pids = getAllPids ++ List.fill(3)(persist(nextPid))
        tp.request(pids.size)
        tp.expectNextUnorderedN(pids)
        tp.expectNoMsg(100.millis)

        val pid4 = persist(nextPid)
        tp.request(1)
        tp.expectNext(pid4)
        tp.expectNoMsg(100.millis)

        val morePids = List.fill(100)(persist(nextPid))
        tp.request(100)
        tp.expectNextUnorderedN(morePids)
        tp.expectNoMsg(100.millis)

        tp.cancel()
      }
    }
  }
}