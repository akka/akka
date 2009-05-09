/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import akka.kernel.config.ActiveObjectGuiceConfigurator
import annotation.oneway
import kernel.config.ScalaConfig._

import com.google.inject.{AbstractModule, Scopes}
import com.jteigen.scalatest.JUnit4Runner

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.matchers._                                                                                      

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext
import org.apache.camel.Endpoint
import org.apache.camel.Exchange
import org.apache.camel.Processor
import org.apache.camel.Producer
import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class CamelSpec extends Spec with ShouldMatchers {

  describe("A Camel routing scheme") {
    it("dummy") {
    }
/*
    it("should route message from actor A to actor B") {
      val latch = new CountDownLatch(1);

      val conf = new ActiveObjectGuiceConfigurator
      conf.configureActiveObjects(
        RestartStrategy(AllForOne, 3, 5000),
            Component(
                "camelfoo",
                classOf[CamelFoo],
                classOf[CamelFooImpl],
                LifeCycle(Permanent, 1000),
                1000) ::
            Component(
                "camelbar",
                classOf[CamelBar],
                classOf[CamelBarImpl],
                LifeCycle(Permanent, 1000),
                1000) ::
            Nil
        ).addRoutes(new RouteBuilder() {
            def configure = {
              from("akka:camelfoo.foo").to("akka:camelbar.bar")
              from("akka:camelbar.bar").process(new Processor() {
                def process(e: Exchange) = {
                  println("Received exchange: " + e.getIn())
                  latch.countDown
                }
              })
            }}
      ).supervise

      //val endpoint = conf.getRoutingEndpoint("akka:camelfoo.foo")
//      println("----- " + endpoint)
//      val exchange = endpoint.createExchange
//      println("----- " + exchange)

      conf.getActiveObject(classOf[CamelFooImpl].getName).asInstanceOf[CamelFoo].foo("Hello Foo")
      
//
//      exchange.getIn().setHeader("cheese", 123)
//      exchange.getIn().setBody("body")
//
//      val producer = endpoint.createProducer
//      println("----- " + producer)
//
//      producer.process(exchange)
//
//      // now lets sleep for a while
//      val received = latch.await(5, TimeUnit.SECONDS)
//      received should equal (true)
//
//      conf.stop
    }
  */
  }
}

trait CamelFoo {
  @oneway def foo(msg: String)
}
trait CamelBar {
  def bar(msg: String): String
}

class CamelFooImpl extends CamelFoo {
  def foo(msg: String) = println("CamelFoo.foo:" + msg)
}
class CamelBarImpl extends CamelBar {
  def bar(msg: String) = msg + "return_bar "
}
