/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import com.google.inject.AbstractModule
import com.google.inject.Scopes

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.config.Supervision._
import akka.dispatch._
import akka.dispatch.FutureTimeoutException
import akka.config.{ Config, TypedActorConfigurator }

@RunWith(classOf[JUnitRunner])
class TypedActorGuiceConfiguratorSpec extends Spec with ShouldMatchers with BeforeAndAfterAll {

  private val conf = new TypedActorConfigurator
  private var messageLog = ""

  override def beforeAll {
    Config.config
    val dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("test").build

    conf.addExternalGuiceModule(new AbstractModule {
      def configure = bind(classOf[Ext]).to(classOf[ExtImpl]).in(Scopes.SINGLETON)
    }).configure(
      AllForOneStrategy(classOf[Exception] :: Nil, 3, 5000),
      List(
        new SuperviseTypedActor(
          classOf[Foo],
          classOf[FooImpl],
          Permanent,
          1000,
          dispatcher),
        new SuperviseTypedActor(
          classOf[Bar],
          classOf[BarImpl],
          Permanent,
          1000,
          dispatcher)).toArray).inject.supervise

  }

  override def afterAll = conf.stop

  describe("TypedActorGuiceConfigurator") {

    it("should inject typed actor using guice") {
      messageLog = ""
      val foo = conf.getInstance(classOf[Foo])
      val bar = conf.getInstance(classOf[Bar])
      bar should equal(foo.getBar)
    }

    it("should inject external dependency using guice") {
      messageLog = ""
      val bar = conf.getInstance(classOf[Bar])
      val ext = conf.getExternalDependency(classOf[Ext])
      ext.toString should equal(bar.getExt.toString)
    }

    it("should lookup non-supervised instance") {
      try {
        val str = conf.getInstance(classOf[String])
        fail("exception should have been thrown")
      } catch {
        case e: Exception ⇒
          classOf[IllegalActorStateException] should equal(e.getClass)
      }
    }

    it("should be able to invoke typed actor") {
      messageLog = ""
      val foo = conf.getInstance(classOf[Foo])
      messageLog += foo.foo("foo ")
      foo.bar("bar ")
      messageLog += "before_bar "
      Thread.sleep(500)
      messageLog should equal("foo return_foo before_bar ")
    }

    it("should be able to invoke typed actor's invocation") {
      messageLog = ""
      val foo = conf.getInstance(classOf[Foo])
      val bar = conf.getInstance(classOf[Bar])
      messageLog += foo.foo("foo ")
      foo.bar("bar ")
      messageLog += "before_bar "
      Thread.sleep(500)
      messageLog should equal("foo return_foo before_bar ")
    }

    it("should throw FutureTimeoutException on time-out") {
      messageLog = ""
      val foo = conf.getInstance(classOf[Foo])
      try {
        foo.longRunning
        fail("exception should have been thrown")
      } catch {
        case e: FutureTimeoutException ⇒
          classOf[FutureTimeoutException] should equal(e.getClass)
      }
    }

    it("should propagate exception") {
      messageLog = ""
      val foo = conf.getInstance(classOf[Foo])
      try {
        foo.throwsException
        fail("exception should have been thrown")
      } catch {
        case e: RuntimeException ⇒
          classOf[RuntimeException] should equal(e.getClass)
      }
    }
  }
}
