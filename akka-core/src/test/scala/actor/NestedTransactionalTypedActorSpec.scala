  /**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.actor._

@RunWith(classOf[JUnitRunner])
class NestedTransactionalTypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  private var messageLog = ""

  override def afterAll {
 //   ActorRegistry.shutdownAll
  }

  describe("Declaratively nested supervised transactional in-memory TypedActor") {

    it("map should not rollback state for stateful server in case of success") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      nested.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested) // transactionrequired
      stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess") should equal("new state")
      nested.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess") should equal("new state")
    }

    it("map should rollback state for stateful server in case of failure") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
      val failer = TypedActor.newInstance(classOf[TypedActorFailer], classOf[TypedActorFailerImpl])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")
      nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")
    }

    it("vector should not rollback state for stateful server in case of success") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      stateful.setVectorState("init") // set init state
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      nested.setVectorState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested) // transactionrequired
      stateful.getVectorState should equal("new state")
      nested.getVectorState should equal("new state")
    }

    it("vector should rollback state for stateful server in case of failure") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      stateful.setVectorState("init") // set init state
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      nested.setVectorState("init") // set init state
      val failer = TypedActor.newInstance(classOf[TypedActorFailer], classOf[TypedActorFailerImpl])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getVectorState should equal("init")
      nested.getVectorState should equal("init")
    }

    it("ref should not rollback state for stateful server in case of success") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      stateful.setRefState("init") // set init state
      nested.setRefState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested)
      stateful.getRefState should equal("new state")
      nested.getRefState should equal("new state")
    }

    it("ref should rollback state for stateful server in case of failure") {
      val stateful = TypedActor.newInstance(classOf[TransactionalTypedActor], classOf[TransactionalTypedActorImpl])
      val nested = TypedActor.newInstance(classOf[NestedTransactionalTypedActor], classOf[NestedTransactionalTypedActorImpl])
      stateful.setRefState("init") // set init state
      nested.setRefState("init") // set init state
      val failer = TypedActor.newInstance(classOf[TypedActorFailer], classOf[TypedActorFailerImpl])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getRefState should equal("init")
      nested.getRefState should equal("init")
    }
  }
}
