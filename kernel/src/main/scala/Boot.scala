/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka

import java.io.File
import java.net.URLClassLoader
import kernel.util.Logging

/**
 * Bootstraps the Akka server by isolating the server classes and all its dependency JARs into its own classloader.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Boot extends Logging {

  val HOME = {
    val home = System.getenv("AKKA_HOME")
    if (home == null) throw new IllegalStateException("No 'AKKA_HOME' environment variable set. You have to set 'AKKA_HOME' to the root of the Akka distribution.")
    else home
  }
  val CLASSES = HOME + "/kernel/target/classes" // FIXME remove for dist
  val LIB = HOME + "/lib"
  val CONFIG = HOME + "/config"
  val DEPLOY = HOME + "/deploy"

  /**
   * Assumes that the AKKA_HOME directory is set with /bin, /config, /deploy and /lib beneath it holding config files and jars.
   * Thus:
   * $AKKA_HOME/bin
   * $AKKA_HOME/config
   * $AKKA_HOME/lib
   * $AKKA_HOME/deploy
   */
  def main(args: Array[String]): Unit = {
    log.info("Bootstrapping Akka server from [AKKA_HOME=%s]", HOME)

    val LIB_DIR = new File(LIB)
    val DEPLOY_DIR = new File(DEPLOY)
    if (!LIB_DIR.exists) { log.error("Could not find a lib directory with all the akka dependencies at [" + DEPLOY + "]"); System.exit(-1) }
    if (!DEPLOY_DIR.exists) { log.error("Could not find a deploy directory at [" + DEPLOY + "]"); System.exit(-1) }

    val toDeploy = for (f <- DEPLOY_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
    if (toDeploy.isEmpty) log.warning("No jars could be found in the [" + DEPLOY + "] directory, nothing to deploy")
    val libs = for (f <- LIB_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL    
    val urls =  new File(CLASSES).toURL :: (libs ::: toDeploy)

    val loader = new URLClassLoader(urls.toArray, ClassLoader.getSystemClassLoader.getParent)
    val mainClass = loader.loadClass(args(0))
    val mainMethod = mainClass.getMethod("main", Array(args.getClass): _*)
    Thread.currentThread.setContextClassLoader(loader)

    val serverArgs = new Array[String](args.length - 1)
    System.arraycopy(args, 1, serverArgs, 0, serverArgs.length)
    mainMethod.invoke(null, Array[Object](serverArgs): _*)
  }
}

