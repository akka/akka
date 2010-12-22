/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.servlet

import akka.config.Config
import akka.actor.Actor
import akka.util. {Switch, Logging, Bootable}

/*
 * This class is responsible for booting up a stack of bundles and then shutting them down
 */
class AkkaLoader extends Logging {
  private val hasBooted = new Switch(false)

  @volatile private var _bundles: Option[Bootable] = None

  def bundles = _bundles;

  /*
   * Boot initializes the specified bundles
   */
  def boot(withBanner: Boolean, b : Bootable): Unit = hasBooted switchOn {
    if (withBanner) printBanner
    log.slf4j.info("Starting Akka...")
    b.onLoad
    Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
    _bundles = Some(b)
    log.slf4j.info("Akka started successfully")
  }

  /*
   * Shutdown, well, shuts down the bundles used in boot
   */
  def shutdown: Unit = hasBooted switchOff {
    log.slf4j.info("Shutting down Akka...")
    _bundles.foreach(_.onUnload)
    _bundles = None
    Actor.shutdownHook.run
    log.slf4j.info("Akka succesfully shut down")
  }

  private def printBanner = {
    log.slf4j.info("==================================================")
    log.slf4j.info("                       t")
    log.slf4j.info("             t       t t")
    log.slf4j.info("            t       t tt   t")
    log.slf4j.info("        tt  t   t  tt       t")
    log.slf4j.info("       t ttttttt  t      ttt t")
    log.slf4j.info("      t   tt ttt t       ttt  t")
    log.slf4j.info("     t     t ttt    t    ttt   t      t")
    log.slf4j.info("    tt    t  ttt         ttt    ttt    t")
    log.slf4j.info("   t     t   ttt         ttt     t tt  t")
    log.slf4j.info("   t         ttt         ttt      t     t")
    log.slf4j.info(" tt          ttt         ttt              t")
    log.slf4j.info("             ttt         ttt")
    log.slf4j.info("   tttttttt  ttt    ttt  ttt    ttt  tttttttt")
    log.slf4j.info("  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt")
    log.slf4j.info("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    log.slf4j.info("  ttt    ttt ttt    ttt  ttt    tt  ttt    ttt")
    log.slf4j.info("        tttt ttttttttt   tttttttt         tttt")
    log.slf4j.info("   ttttttttt ttt    ttt  ttt   ttt   ttttttttt")
    log.slf4j.info("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    log.slf4j.info("  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt")
    log.slf4j.info("  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt")
    log.slf4j.info("   tttttttt  ttt    ttt  ttt    ttt  tttttttt")
    log.slf4j.info("==================================================")
    log.slf4j.info("            Running version {}", Config.VERSION)
    log.slf4j.info("==================================================")
  }
}
