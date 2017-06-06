package akka.persistence.query

/**
 * This spec aims to verify custom akka-persistence Query implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your query plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 */
trait CurrentPersistenceIdsQuerySpec { _: QuerySpec ⇒
  "CurrentPersistenceIdsQuery" must {
    "find existing persistenceIds" in {
      val pids = getAllPids ++ List.fill(3)(persist(nextPid))
      withCurrentPersistenceIdsQuery() { tp ⇒
        tp.request(pids.size)
        tp.expectNextUnorderedN(pids)
        tp.expectComplete()
      }
    }
  }
}