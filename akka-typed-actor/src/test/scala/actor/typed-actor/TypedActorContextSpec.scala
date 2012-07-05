/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.dispatch.ActorCompletableFuture;

@RunWith(classOf[JUnitRunner])
class TypedActorContextSpec extends Spec with ShouldMatchers with BeforeAndAfterAll {

  describe("TypedActorContext") {
    it("context.sender should return the sender TypedActor reference") {
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val pojoCaller = TypedActor.newInstance(classOf[SimpleJavaPojoCaller], classOf[SimpleJavaPojoCallerImpl])
      pojoCaller.setPojo(pojo)
      pojoCaller.getSenderFromSimpleJavaPojo.isInstanceOf[Option[_]] should equal(true)
      pojoCaller.getSenderFromSimpleJavaPojo.asInstanceOf[Option[_]].isDefined should equal(true)
      pojoCaller.getSenderFromSimpleJavaPojo.asInstanceOf[Option[_]].get should equal(pojoCaller)
    }
    it("context.senderFuture should return the senderFuture TypedActor reference") {
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val pojoCaller = TypedActor.newInstance(classOf[SimpleJavaPojoCaller], classOf[SimpleJavaPojoCallerImpl])
      pojoCaller.setPojo(pojo)
      pojoCaller.getSenderFutureFromSimpleJavaPojo.getClass.getName should equal(classOf[ActorCompletableFuture].getName)
    }
  }
}
