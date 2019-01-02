/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.discovery

import akka.actor.ActorSystem
import akka.discovery.{ Discovery, Lookup, ServiceDiscovery }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object CompileOnlySpec {

  //#loading
  val system = ActorSystem()
  val serviceDiscovery = Discovery(system).discovery
  //#loading

  //#basic
  serviceDiscovery.lookup(Lookup("akka.io"), 1.second)
  // Convenience for a Lookup with only a serviceName
  serviceDiscovery.lookup("akka.io", 1.second)
  //#basic

  //#full
  val lookup: Future[ServiceDiscovery.Resolved] = serviceDiscovery.lookup(Lookup("akka.io").withPortName("remoting").withProtocol("tcp"), 1.second)
  //#full

  // compiler
  lookup.foreach(println)

}
