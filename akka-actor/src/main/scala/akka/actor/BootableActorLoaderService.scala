/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import java.io.File
import java.net.{URL, URLClassLoader}
import java.util.jar.JarFile

import akka.util.{Bootable, Logging}
import akka.config.Config._

/**
 * Handles all modules in the deploy directory (load and unload)
 */
trait BootableActorLoaderService extends Bootable with Logging {

  val BOOT_CLASSES = config.getList("akka.boot")
  lazy val applicationLoader: Option[ClassLoader] = createApplicationClassLoader

  protected def createApplicationClassLoader : Option[ClassLoader] = {
    Some(
    if (HOME.isDefined) {
      val CONFIG = HOME.getOrElse(throwNoAkkaHomeException) + "/config"
      val DEPLOY = HOME.getOrElse(throwNoAkkaHomeException) + "/deploy"
      val DEPLOY_DIR = new File(DEPLOY)
      if (!DEPLOY_DIR.exists) {
        log.error("Could not find a deploy directory at [%s]", DEPLOY)
        System.exit(-1)
      }
      val filesToDeploy = DEPLOY_DIR.listFiles.toArray.toList
        .asInstanceOf[List[File]].filter(_.getName.endsWith(".jar"))
      var dependencyJars: List[URL] = Nil
      filesToDeploy.map { file =>
        val jarFile = new JarFile(file)
        val en = jarFile.entries
        while (en.hasMoreElements) {
          val name = en.nextElement.getName
          if (name.endsWith(".jar")) dependencyJars ::= new File(
            String.format("jar:file:%s!/%s", jarFile.getName, name)).toURI.toURL
        }
      }
      val toDeploy = filesToDeploy.map(_.toURI.toURL)
      log.info("Deploying applications from [%s]: [%s]", DEPLOY, toDeploy)
      log.debug("Loading dependencies [%s]", dependencyJars)
      val allJars = toDeploy ::: dependencyJars

      new URLClassLoader(allJars.toArray,Thread.currentThread.getContextClassLoader)
    } else Thread.currentThread.getContextClassLoader)
  }

  abstract override def onLoad = {
    applicationLoader.foreach(_ => log.info("Creating /deploy class-loader"))

    super.onLoad

    for (loader <- applicationLoader; clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
  }

  abstract override def onUnload = {
    super.onUnload
    ActorRegistry.shutdownAll
  }
}
