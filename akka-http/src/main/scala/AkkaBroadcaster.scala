/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.comet

import org.atmosphere.cpr.{AtmosphereResourceEvent, AtmosphereResource}
import se.scalablesolutions.akka.actor.Actor._

class AkkaBroadcaster extends org.atmosphere.jersey.JerseyBroadcaster {
  name = classOf[AkkaBroadcaster].getName
  
  val caster = actor { case f : Function0[_] => f() }

  override def destroy {
    super.destroy
    caster.stop
  }
  
  protected override def broadcast(r :  AtmosphereResource[_,_], e : AtmosphereResourceEvent[_,_]) = {
    caster ! (() => super.broadcast(r,e))
  }
}