/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import java.io.File
import java.net.{URL, URLClassLoader}
import java.util.jar.JarFile
import java.util.Enumeration

import se.scalablesolutions.akka.util.{Bootable, Logging}
import se.scalablesolutions.akka.config.Config._

class AkkaDeployClassLoader(urls : List[URL], parent : ClassLoader) extends URLClassLoader(urls.toArray.asInstanceOf[Array[URL]],parent)
{
  override def findResources(resource : String) = {
    val normalResult = super.findResources(resource)
    if(normalResult.hasMoreElements) normalResult else findDeployed(resource)
  }

  def findDeployed(resource : String) = new Enumeration[URL]{
    private val it =  getURLs.flatMap( listClassesInPackage(_,resource) ).iterator
    def hasMoreElements = it.hasNext
    def nextElement = it.next
  }

  def listClassesInPackage(jar : URL, pkg : String) = {
        val f  = new File(jar.getFile)
    val jf = new JarFile(f)
    try {
      val es = jf.entries
      var result = List[URL]()
      while(es.hasMoreElements)
      {
        val e = es.nextElement
        if(!e.isDirectory && e.getName.startsWith(pkg) && e.getName.endsWith(".class"))
        result ::= new URL("jar:" + f.toURI.toURL + "!/" + e)
      }
    result
    } finally {
      jf.close
    }
  }
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

      new AkkaDeployClassLoader(allJars,Thread.currentThread.getContextClassLoader)
    } else Thread.currentThread.getContextClassLoader)
  }

  abstract override def onLoad = {
    for (loader <- applicationLoader; clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
    super.onLoad
  }

  abstract override def onUnload = {
    super.onUnload
    ActorRegistry.shutdownAll
  }
}
