/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.osgi

import org.osgi.framework.{BundleActivator, BundleContext}


class Activator extends BundleActivator {

  def start(context: BundleContext) {
    println("Start")
    val osgiActor = new OSGiActor
    //osgiActor ! "Hello"
    Unit
  }

  def stop(context: BundleContext) {
    println("stop")
  }

}

