/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.servlet

import akka.config.Config
import akka.actor.Actor
import akka.util. {Switch, Bootable}

/*
 * This class is responsible for booting up a stack of bundles and then shutting them down
 */
class AkkaLoader {
  private val hasBooted = new Switch(false)

  @volatile private var _bundles: Option[Bootable] = None

  def bundles = _bundles;

  /*
   * Boot initializes the specified bundles
   */
  def boot(withBanner: Boolean, b : Bootable): Unit = hasBooted switchOn {
    if (withBanner) printBanner
    println("Starting Akka...")
    b.onLoad
    Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
    _bundles = Some(b)
    println("Akka started successfully")
  }

  /*
   * Shutdown, well, shuts down the bundles used in boot
   */
  def shutdown: Unit = hasBooted switchOff {
    println("Shutting down Akka...")
    _bundles.foreach(_.onUnload)
    _bundles = None
    Actor.shutdownHook.run
    println("Akka succesfully shut down")
  }

  private def printBanner = {
    println("==================================================")
    println("                       t")
    println("             t       t t")
    println("            t       t tt   t")
    println("        tt  t   t  tt       t")
    println("       t ttttttt  t      ttt t")
    println("      t   tt ttt t       ttt  t")
    println("     t     t ttt    t    ttt   t      t")
    println("    tt    t  ttt         ttt    ttt    t")
    println("   t     t   ttt         ttt     t tt  t")
    println("   t         ttt         ttt      t     t")
    println(" tt          ttt         ttt              t")
    println("             ttt         ttt")
    println("   tttttttt  ttt    ttt  ttt    ttt  tttttttt")
    println("  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt")
    println("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    println("  ttt    ttt ttt    ttt  ttt    tt  ttt    ttt")
    println("        tttt ttttttttt   tttttttt         tttt")
    println("   ttttttttt ttt    ttt  ttt   ttt   ttttttttt")
    println("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    println("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    println("  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt")
    println("   tttttttt  ttt    ttt  ttt    ttt  tttttttt")
    println("==================================================")
    println("            Running version {}", Config.VERSION)
    println("==================================================")
  }
}
