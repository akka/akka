/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.supervisor

import org.specs.runner.JUnit4
import org.specs.Specification

import scala.actors._
import scala.actors.Actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorStateTest extends JUnit4(supervisorStateSpec) // for JUnit4 and Maven
object supervisorStateSpec extends Specification {
  val dummyActor = new GenericServer { override def body: PartialFunction[Any, Unit] = { case _ => }}
  val newDummyActor = () => dummyActor
  var state: SupervisorState = _
  var proxy: GenericServerContainer = _
  var supervisor: Supervisor = _

  proxy = new GenericServerContainer("server1", newDummyActor)
  object factory extends SupervisorFactory {
    override def getSupervisorConfig: SupervisorConfig = {
      SupervisorConfig(
        RestartStrategy(AllForOne, 3, 100),
        Worker(
          proxy,
          LifeCycle(Permanent, 100))
        :: Nil)
    }
  }

  supervisor = factory.newSupervisor
  state = new SupervisorState(supervisor, new AllForOneStrategy(3, 100))

  "supervisor state should return added server" in {
    state.addServerContainer(proxy)
    state.getServerContainer("server1") match {
      case None => fail("should have returned server")
      case Some(server) =>
        assert(server != null)
        assert(server.isInstanceOf[GenericServerContainer])
        assert(proxy === server)
    }
  }

  "supervisor state should remove added server" in {
    state.addServerContainer(proxy)

    state.removeServerContainer("server1")
    state.getServerContainer("server1") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
    state.getServerContainer("dummyActor") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
  }

  "supervisor state should fail getting non-existent server by symbol" in {
    state.getServerContainer("server2") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
  }

  "supervisor state should fail getting non-existent server by actor" in {
    state.getServerContainer("dummyActor") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
  }
}
