/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.comet

import org.atmosphere.cpr.{AtmosphereResourceEvent, AtmosphereResource}

import akka.actor.Actor._
import akka.actor.Actor
import akka.dispatch.Dispatchers
import org.atmosphere.jersey.util.JerseyBroadcasterUtil

object AkkaBroadcaster {
  val broadcasterDispatcher = Dispatchers.fromConfig("akka.http.comet-dispatcher")

  type Event    = AtmosphereResourceEvent[_,_]
  type Resource = AtmosphereResource[_,_]
}

class AkkaBroadcaster extends org.atmosphere.jersey.util.JerseySimpleBroadcaster {
  import AkkaBroadcaster._

  //FIXME should be supervised
  lazy val caster = actorOf(new Actor {
    self.dispatcher = broadcasterDispatcher
    def receive = {
      case (r: Resource,e: Event) => JerseyBroadcasterUtil.broadcast(r,e)
    }
  }).start

  override def destroy {
    super.destroy
    caster.stop
  }

  protected override def broadcast(r: Resource, e : Event) {
    caster ! ((r,e))
  }
}
