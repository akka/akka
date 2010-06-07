/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.servlet

import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.util.{Logging, Bootable}

/*
 * This class is responsible for booting up a stack of bundles and then shutting them down
 */
class AkkaLoader extends Logging {
  @volatile private var hasBooted = false

  @volatile private var _bundles: Option[Bootable] = None

  def bundles = _bundles;

  /*
   * Boot initializes the specified bundles
   */
  def boot(withBanner: Boolean, b : Bootable): Unit = synchronized {
    if (!hasBooted) {
      if (withBanner) printBanner
      log.info("Starting Akka...")
      b.onLoad
      Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
      log.info("Akka started successfully")
      hasBooted = true
      _bundles = Some(b)
    }
  }

  /*
   * Shutdown, well, shuts down the bundles used in boot
   */
  def shutdown = synchronized {
    if (hasBooted) {
      log.info("Shutting down Akka...")
      _bundles.foreach(_.onUnload)
      _bundles = None
      log.info("Akka succesfully shut down")
    }
  }

  private def printBanner = {
    log.info(
"""
                       t
             t       t t
            t       t tt   t
        tt  t   t  tt       t
       t ttttttt  t      ttt t
      t   tt ttt t       ttt  t
     t     t ttt    t    ttt   t      t
    tt    t  ttt         ttt    ttt    t
   t     t   ttt         ttt     t tt  t
   t         ttt         ttt      t     t
 tt          ttt         ttt              t
             ttt         ttt
   tttttttt  ttt    ttt  ttt    ttt  tttttttt
  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt
  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt
  ttt    ttt ttt    ttt  ttt    tt  ttt    ttt
        tttt ttttttttt   tttttttt         tttt
   ttttttttt ttt    ttt  ttt   ttt   ttttttttt
  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt
  ttt    ttt ttt    ttt  ttt    ttt ttt    ttt
  ttt    tt  ttt    ttt  ttt    ttt ttt    ttt
   tttttttt  ttt    ttt  ttt    ttt  tttttttt

==================================================
""")
    log.info("            Running version %s", Config.VERSION)
    log.info("==================================================")
  }
}
