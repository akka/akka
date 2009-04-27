/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.scalatest._

import scala.actors.Actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class SupervisorStateSpec extends Suite {

  val dummyActor = new GenericServer { override def body: PartialFunction[Any, Unit] = { case _ => }}
  val newDummyActor = () => dummyActor
  var state: SupervisorState = _
  var proxy: GenericServerContainer = _
  var supervisor: Supervisor = _

  def setup = {
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
  }

  def testAddServer = {
    setup
    state.addServerContainer(proxy)
    state.getServerContainer("server1") match {
      case None => fail("should have returned server")
      case Some(server) =>
        assert(server != null)
        assert(server.isInstanceOf[GenericServerContainer])
        assert(proxy === server)
    }
  }

  def testGetServer = {
    setup
    state.addServerContainer(proxy)
    state.getServerContainer("server1") match {
      case None => fail("should have returned server")
      case Some(server) =>
        assert(server != null)
        assert(server.isInstanceOf[GenericServerContainer])
        assert(proxy === server)
    }
  }

  def testRemoveServer = {
    setup
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

  def testGetNonExistingServerBySymbol = {
    setup
    state.getServerContainer("server2") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
  }

  def testGetNonExistingServerByActor = {
    setup
    state.getServerContainer("dummyActor") match {
      case Some(_) => fail("should have returned None")
      case None =>
    }
  }
}
