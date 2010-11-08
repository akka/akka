/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package sample.mist

import akka.actor._
import akka.actor.Actor._
import akka.config.Supervision._
import akka.http._


/**
 * Starts up the base services for http (jetty)
 */
class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), 3, 100),
      Supervise(
        actorOf[ServiceRoot],
        Permanent) ::
      Supervise(
        actorOf[SimpleService],
        Permanent)
      :: Nil))
  factory.newInstance.start
}


class ServiceRoot extends RootEndpoint
{
  //
  // use the configurable dispatcher
  //
  self.dispatcher = Endpoint.Dispatcher

  //
  // TODO: make this a config prop
  //
  self.id = "DefaultRootEndpoint"
}

