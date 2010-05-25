/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import java.io.File
import java.net.{URL, URLClassLoader}
import java.util.jar.JarFile

import se.scalablesolutions.akka.util.{Bootable, Logging}
import se.scalablesolutions.akka.config.Config._

object ActorModules {
  import java.util.concurrent.atomic.AtomicReference
  private val _loader = new AtomicReference[Option[ClassLoader]](None)
  def loader_? = _loader.get
  private[actor] def loader_?(cl : Option[ClassLoader]) = _loader.set(cl)
}

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

      URLClassLoader.newInstance(
        allJars.toArray.asInstanceOf[Array[URL]],
        ClassLoader.getSystemClassLoader)
    } else getClass.getClassLoader)
  }

  abstract override def onLoad = {
	ActorModules.loader_?(applicationLoader)
    for (loader <- applicationLoader; clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
    super.onLoad
  }

  abstract override def onUnload = ActorRegistry.shutdownAll
}
