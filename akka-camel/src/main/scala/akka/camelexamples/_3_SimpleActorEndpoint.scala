/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import org.apache.camel.builder.RouteBuilder
import akka.actor.{Props, ActorSystem}
import akka.camel._
import RichString._

object  _3_SimpleActorEndpoint extends App{

  val system = ActorSystem("test")
  val camel = CamelExtension(system)

  val actor = system.actorOf(Props[SysOutActor])
  
  camel.context.addRoutes(new RouteBuilder() {
    def configure() {
      from("file://data/input/CamelConsumer").to(actor)
    }
  })
  
  "data/input/CamelConsumer/file1.txt" << "test data "+math.random

  Thread.sleep(3000)

  system.shutdown()
  
}