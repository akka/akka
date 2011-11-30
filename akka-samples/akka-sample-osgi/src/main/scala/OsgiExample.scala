/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.osgi

import org.osgi.framework.{ BundleActivator, BundleContext }
import akka.actor.{ Timeout, ActorSystem, Actor }

class Activator extends BundleActivator {
  val system = ActorSystem()

  def start(context: BundleContext) {
    println("Starting the OSGi example ...")
    val echo = system.actorOf[EchoActor]
    val answer = (echo ? ("OSGi example", Timeout(100))).as[String]
    println(answer getOrElse "No answer!")
  }

  def stop(context: BundleContext) {
    system.stop()
    println("Stopped the OSGi example.")
  }
}

class EchoActor extends Actor {
  override def receive = {
    case x ⇒ sender ! x
  }
}
