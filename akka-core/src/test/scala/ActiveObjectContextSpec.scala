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

import se.scalablesolutions.akka.dispatch.DefaultCompletableFuture;

@RunWith(classOf[JUnitRunner])
class ActiveObjectContextSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("ActiveObjectContext") {
    it("context.sender should return the sender Active Object reference") {
      val pojo = ActiveObject.newInstance(classOf[SimpleJavaPojo])
      val pojoCaller = ActiveObject.newInstance(classOf[SimpleJavaPojoCaller])
      pojoCaller.setPojo(pojo)
      try { 
        pojoCaller.getSenderFromSimpleJavaPojo should equal (pojoCaller)
      } catch {
        case e => fail("no sender available")
      }
    }

    it("context.senderFuture should return the senderFuture Active Object reference") {
      val pojo = ActiveObject.newInstance(classOf[SimpleJavaPojo])
      val pojoCaller = ActiveObject.newInstance(classOf[SimpleJavaPojoCaller])
      pojoCaller.setPojo(pojo)
      try { 
        pojoCaller.getSenderFutureFromSimpleJavaPojo.getClass.getName should equal (classOf[DefaultCompletableFuture[_]].getName)
      } catch {
        case e => fail("no sender future available", e)
      }
    }
  }
}
