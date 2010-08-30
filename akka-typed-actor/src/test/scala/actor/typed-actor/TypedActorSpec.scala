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
class TypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("TypedActor") {
    it("should resolve Future return from method defined to return a Future") {
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val future = pojo.square(10)
      future.await
      future.result.isDefined should equal (true)
      future.result.get should equal (100)
    }
  }
}
