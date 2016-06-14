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
      val persistenceIds = List("pid-1", "pid-2", "pid-3")
      persistenceIds.foreach(persistEventFor)

      withCurrentPersistenceIdsQuery() { tp ⇒
        tp.request(3)
        tp.expectNextUnorderedN(persistenceIds)
        tp.expectComplete()
      }
    }
  }
}