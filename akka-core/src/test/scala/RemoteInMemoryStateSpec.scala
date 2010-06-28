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

import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.config.ActiveObjectConfigurator
import se.scalablesolutions.akka.remote.RemoteNode

@RunWith(classOf[JUnitRunner])
class RemoteInMemoryStateSpec extends
  Spec with 
  ShouldMatchers with 
  BeforeAndAfterAll {  

  private val conf = new ActiveObjectConfigurator
  private var messageLog = ""
  
  override def beforeAll = {
    Config.config
    new Thread(new Runnable {
       def run = RemoteNode.start
    }).start
    Thread.sleep(1000)
  }
  
  override def afterAll = conf.stop

  describe("Remote transactional in-memory Active Object ") {

    it("map should not rollback state for stateful server in case of success") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
      stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess") should equal("new state")
    }

    it("map should rollback state for stateful server in case of failure") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
      val failer =ActiveObject.newRemoteInstance(classOf[InMemFailer], 1000, "localhost", 9999) //conf.getInstance(classOf[InMemFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")
    }

    it("vector should not rollback state for stateful server in case of success") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setVectorState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
      stateful.getVectorState should equal("new state")
    }

    it("vector should rollback state for stateful server in case of failure") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setVectorState("init") // set init state
      val failer =ActiveObject.newRemoteInstance(classOf[InMemFailer], 1000, "localhost", 9999) //conf.getInstance(classOf[InMemFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getVectorState should equal("init")
    }

    it("ref should not rollback state for stateful server in case of success") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setRefState("init") // set init state
      stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
      stateful.getRefState should equal("new state")
    }

    it("ref should rollback state for stateful server in case of failure") {
      val stateful =  ActiveObject.newRemoteInstance(classOf[InMemStateful], 1000, "localhost", 9999)
      stateful.setRefState("init") // set init state
      val failer =ActiveObject.newRemoteInstance(classOf[InMemFailer], 1000, "localhost", 9999) //conf.getInstance(classOf[InMemFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getRefState should equal("init")
    }
  }
}
