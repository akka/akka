/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package sample.rest.scala

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
        //
        // in this particular case, just boot the built-in default root endpoint
        //
      Supervise(
        actorOf[RootEndpoint],
        Permanent) ::
      Supervise(
        actorOf[SimpleAkkaAsyncHttpService],
        Permanent)
      :: Nil))
  factory.newInstance.start
}

