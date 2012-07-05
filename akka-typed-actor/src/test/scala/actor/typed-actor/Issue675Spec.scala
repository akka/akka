/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import Issue675Spec._

object Issue675Spec {
  var l = collection.mutable.ListBuffer.empty[String]

  trait RegistrationService {
    def register(user: String, cred: String): Unit
  }

  class RegistrationServiceImpl extends TypedActor with RegistrationService {
    def register(user: String, cred: String): Unit = {}

    override def preStart() {
      l += "RegistrationServiceImpl.preStart() called"
    }
  }
}

@RunWith(classOf[JUnitRunner])
class Issue675Spec extends Spec with ShouldMatchers with BeforeAndAfterEach {

  override def afterEach() {
    Actor.registry.shutdownAll()
  }

  describe("TypedActor preStart method") {
    it("should be invoked once") {
      import Issue675Spec._
      val simplePojo = TypedActor.newInstance(classOf[RegistrationService], classOf[RegistrationServiceImpl])
      l.size should equal(1)
    }
  }
}
