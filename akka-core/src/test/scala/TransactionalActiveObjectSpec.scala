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
class TransactionalActiveObjectSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  private var messageLog = ""

  override def afterAll {
//    ActorRegistry.shutdownAll
  }
  
  describe("Declaratively supervised transactional in-memory Active Object ") {
    it("map should not rollback state for stateful server in case of success") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init")
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state")
      stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess") should equal("new state")
    }

    it("map should rollback state for stateful server in case of failure") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init")
      val failer = ActiveObject.newInstance(classOf[ActiveObjectFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")
    }

    it("vector should not rollback state for stateful server in case of success") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setVectorState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state")
      stateful.getVectorState should equal("new state")
    }

    it("vector should rollback state for stateful server in case of failure") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setVectorState("init") // set init state
      val failer = ActiveObject.newInstance(classOf[ActiveObjectFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getVectorState should equal("init")
    }

    it("ref should not rollback state for stateful server in case of success") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setRefState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state")
      stateful.getRefState should equal("new state")
    }

    it("ref should rollback state for stateful server in case of failure") {
      val stateful = ActiveObject.newInstance(classOf[TransactionalActiveObject])
      stateful.init
      stateful.setRefState("init") // set init state
      val failer = ActiveObject.newInstance(classOf[ActiveObjectFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer)
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getRefState should equal("init")
    }
  }
}
