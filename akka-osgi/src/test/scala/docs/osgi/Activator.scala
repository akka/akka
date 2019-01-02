/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.osgi

case object SomeMessage

class SomeActor extends akka.actor.Actor {
  def receive = { case SomeMessage ⇒ }
}

//#Activator
import akka.actor.{ Props, ActorSystem }
import org.osgi.framework.BundleContext
import akka.osgi.ActorSystemActivator

class Activator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem): Unit = {
    // optionally register the ActorSystem in the OSGi Service Registry
    registerService(context, system)

    val someActor = system.actorOf(Props[SomeActor], name = "someName")
    someActor ! SomeMessage
  }

}
//#Activator
