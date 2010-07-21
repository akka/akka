/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka
package sample.osgi

import actor.{ Actor, ActorRegistry }
import actor.Actor._

import org.osgi.framework.{ BundleActivator, BundleContext }

class Activator extends BundleActivator {

  def start(context: BundleContext) {
    println("Starting the OSGi example ...")
    val echo = actorOf[EchoActor].start
    val answer = (echo !! "OSGi example")
    println(answer getOrElse "No answer!")
  }

  def stop(context: BundleContext) {
	  ActorRegistry.shutdownAll()
    println("Stopped the OSGi example.")
  }
}

class EchoActor extends Actor {

  override def receive = {
	  case x => self reply x
  }	
}
