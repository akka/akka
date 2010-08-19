/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.comet

import org.atmosphere.cpr.{AtmosphereResourceEvent, AtmosphereResource}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.dispatch.Dispatchers

class AkkaBroadcaster extends org.atmosphere.jersey.JerseyBroadcaster {
  name = classOf[AkkaBroadcaster].getName

  val broadcasterDispatcher = Dispatchers.fromConfig("akka.rest.comet-dispatcher")

  //FIXME should be supervised
  val caster = actorOf(new Actor {
    self.dispatcher = broadcasterDispatcher
    def receive = {
      case f : Function0[_] => f()
    }
  }).start

  override def destroy {
    super.destroy
    caster.stop
  }

  protected override def broadcast(r :  AtmosphereResource[_,_], e : AtmosphereResourceEvent[_,_]) = {
    caster ! (() => super.broadcast(r,e))
  }
}
