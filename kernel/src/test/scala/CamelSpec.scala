/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import akka.kernel.config.ActiveObjectGuiceConfigurator
import annotation.oneway
import kernel.config.ScalaConfig._

import com.google.inject.{AbstractModule, Scopes}
import com.jteigen.scalatest.JUnit4Runner

import org.apache.camel.component.bean.ProxyHelper
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

// REQUIRES: -Djava.naming.factory.initial=org.apache.camel.util.jndi.CamelInitialContextFactory

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class CamelSpec extends Spec with ShouldMatchers {

  describe("A Camel routing scheme") {
    it("should route message from direct:test to actor A using @Bean endpoint") {
/*
      val latch = new CountDownLatch(1);

      val conf = new ActiveObjectGuiceConfigurator
      conf.configure(
        RestartStrategy(AllForOne, 3, 5000),
            Component(
                "camelfoo",
                classOf[CamelFoo],
                classOf[CamelFooImpl],
                LifeCycle(Permanent, 1000),
                1000) ::
            Nil
        ).addRoutes(new RouteBuilder() {
            def configure = {
              from("direct:test").to("bean:camelfoo").process(new Processor() {
                def process(e: Exchange) = {
                  println("Received exchange: " + e.getIn())
                  latch.countDown
                }
              })
            }}
      ).supervise

      val endpoint = conf.getRoutingEndpoint("direct:test")
      val proxy = ProxyHelper.createProxy(endpoint, classOf[CamelFoo])

      proxy.foo("hello there")

      val exchange = endpoint.createExchange
      println("----- " + exchange)

      exchange.getIn().setBody("hello there")

      val producer = endpoint.createProducer
      println("----- " + producer)

      producer.process(exchange)

      // now lets sleep for a while
      val received = latch.await(5, TimeUnit.SECONDS)
      received should equal (true)
      conf.stop
*/
    }
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
