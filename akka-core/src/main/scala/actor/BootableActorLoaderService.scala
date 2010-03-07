/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
 
package se.scalablesolutions.akka.actor

import java.io.File
import java.net.URLClassLoader

import se.scalablesolutions.akka.util.{Bootable,Logging}
import se.scalablesolutions.akka.Config._

/**
 * Handles all modules in the deploy directory (load and unload)
 */
trait BootableActorLoaderService extends Bootable with Logging {

  val BOOT_CLASSES = config.getList("akka.boot")
  lazy val applicationLoader: Option[ClassLoader] = createApplicationClassLoader
  
  protected def createApplicationClassLoader : Option[ClassLoader] = {
    Some(
    if (HOME.isDefined) {
      val CONFIG = HOME.get + "/config"
      val DEPLOY = HOME.get + "/deploy"
      val DEPLOY_DIR = new File(DEPLOY)
      if (!DEPLOY_DIR.exists) {
        log.error("Could not find a deploy directory at [%s]", DEPLOY)
        System.exit(-1)
      }
      val toDeploy = for (f <- DEPLOY_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
      log.info("Deploying applications from [%s]: [%s]", DEPLOY, toDeploy.toArray.toList)
      new URLClassLoader(toDeploy.toArray, ClassLoader.getSystemClassLoader)
    } else getClass.getClassLoader)
  }

  abstract override def onLoad = {
    for (loader <- applicationLoader; clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
    super.onLoad
  }
  
  abstract override def onUnload = ActorRegistry.shutdownAll
}