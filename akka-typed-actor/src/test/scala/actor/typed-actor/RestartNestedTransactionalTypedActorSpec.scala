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
import se.scalablesolutions.akka.config._
import se.scalablesolutions.akka.config.TypedActorConfigurator
import se.scalablesolutions.akka.config.JavaConfig._
import se.scalablesolutions.akka.actor._

@RunWith(classOf[JUnitRunner])
class RestartNestedTransactionalTypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  private val conf = new TypedActorConfigurator
  private var messageLog = ""

  override def beforeAll {
    /*
    Config.config
    conf.configure(
      new RestartStrategy(new AllForOne, 3, 5000, List(classOf[Exception]).toArray),
        List(
          new Component(classOf[TransactionalTypedActor],
            new LifeCycle(new Permanent),
            10000),
          new Component(classOf[NestedTransactionalTypedActor],
            new LifeCycle(new Permanent),
            10000),
          new Component(classOf[TypedActorFailer],
            new LifeCycle(new Permanent),
            10000)
        ).toArray).supervise
        */
  }

  override def afterAll {
    /*
    conf.stop
    ActorRegistry.shutdownAll
    */
  }

  describe("Restart nested supervised transactional Typed Actor") {
/*
    it("map should rollback state for stateful server in case of failure") {
      val stateful = conf.getInstance(classOf[TransactionalTypedActor])
      stateful.init
      stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state

      val nested = conf.getInstance(classOf[NestedTransactionalTypedActor])
      nested.init
      nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state

      val failer = conf.getInstance(classOf[TypedActorFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)

        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")

      nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure") should equal("init")
    }

    it("vector should rollback state for stateful server in case of failure") {
      val stateful = conf.getInstance(classOf[TransactionalTypedActor])
      stateful.init
      stateful.setVectorState("init") // set init state

      val nested = conf.getInstance(classOf[NestedTransactionalTypedActor])
      nested.init
      nested.setVectorState("init") // set init state

      val failer = conf.getInstance(classOf[TypedActorFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)

        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getVectorState should equal("init")

      nested.getVectorState should equal("init")
    }

    it("ref should rollback state for stateful server in case of failure") {
      val stateful = conf.getInstance(classOf[TransactionalTypedActor])
      stateful.init
      val nested = conf.getInstance(classOf[NestedTransactionalTypedActor])
      nested.init
      stateful.setRefState("init") // set init state

      nested.setRefState("init") // set init state

      val failer = conf.getInstance(classOf[TypedActorFailer])
      try {
        stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer)

        fail("should have thrown an exception")
      } catch { case e => {} }
      stateful.getRefState should equal("init")

      nested.getRefState should equal("init")
    }
    */
  }
}
