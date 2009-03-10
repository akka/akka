/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import java.io.File
import java.lang.reflect.Method
import java.net.{URL, URLClassLoader}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Boot extends Logging {

  /**
   * Assumes that the AKKA_HOME directory is set with /config, /classes and /lib beneath it holding files and jars.
   * Thus:
   * $AKKA_HOME
   * $AKKA_HOME/bin
   * $AKKA_HOME/classes
   * $AKKA_HOME/lib
   * $AKKA_HOME/config
   */
  def main(args: Array[String]) = {
    val HOME = System.getProperty("AKKA_HOME", "..")
    val CLASSES = HOME + "/classes"
    val LIB = HOME + "/lib"
    val CONFIG = HOME + "/config"

    log.info("Bootstrapping Akka server from AKKA_HOME=" + HOME)

    val libs = for (f <- new File(LIB).listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
    val urls = new File(CLASSES).toURL :: libs
    val loader = new URLClassLoader(urls.toArray, ClassLoader.getSystemClassLoader.getParent)
    val mainClass = loader.loadClass(args(0))
    val mainMethod = mainClass.getMethod("main", Array(args.getClass): _*)
    Thread.currentThread.setContextClassLoader(loader)

    val serverArgs = new Array[String](args.length - 1)
    System.arraycopy(args, 1, serverArgs, 0, serverArgs.length)
    mainMethod.invoke(null, Array[Object](serverArgs): _*)
  }
}

