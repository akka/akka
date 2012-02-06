/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.kernel

import akka.actor.ActorSystem
import java.io.File
import java.lang.Boolean.getBoolean
import java.net.URLClassLoader
import java.util.jar.JarFile
import scala.collection.JavaConverters._

/**
 * To use the microkernel at least one 'boot class' needs to be specified.
 * A boot class implements this interface ([[akka.kernel.Bootable]]) and
 * must have an empty default constructor.
 *
 * ActorSystems can be created within the boot class.
 *
 * An example of a simple boot class:
 * {{{
 * class BootApp extends Bootable {
 *   val system = ActorSystem("app")
 *
 *   def startup = {
 *     system.actorOf(Props[FirstActor]) ! FirstMessage
 *   }
 *
 *   def shutdown = {
 *     system.shutdown()
 *   }
 * }
 * }}}
 *
 * Boot classes are specified as main arguments to the microkernel.
 *
 * For example, using the akka script an application can be started with
 * the following at the command line:
 * {{{
 * bin/akka org.app.BootApp
 * }}}
 */
trait Bootable {
  /**
   * Callback run on microkernel startup.
   * Create initial actors and messages here.
   */
  def startup(): Unit

  /**
   * Callback run on microkernel shutdown.
   * Shutdown actor systems here.
   */
  def shutdown(): Unit
}

/**
 * Main class for running the microkernel.
 */
object Main {
  val quiet = getBoolean("akka.kernel.quiet")

  def log(s: String) = if (!quiet) println(s)

  def main(args: Array[String]) = {
    if (args.isEmpty) {
      log("[error] No boot classes specified")
      System.exit(1)
    }

    log(banner)
    log("Starting Akka...")
    log("Running Akka " + ActorSystem.Version)

    val classLoader = createClassLoader()

    Thread.currentThread.setContextClassLoader(classLoader)

    val bootClasses: Seq[String] = args.toSeq
    val bootables: Seq[Bootable] = bootClasses map { c ⇒ classLoader.loadClass(c).newInstance.asInstanceOf[Bootable] }

    for (bootable ← bootables) {
      log("Starting up " + bootable.getClass.getName)
      bootable.startup()
    }

    addShutdownHook(bootables)

    log("Successfully started Akka")
  }

  def createClassLoader(): ClassLoader = {
    if (ActorSystem.GlobalHome.isDefined) {
      val home = ActorSystem.GlobalHome.get
      val deploy = new File(home, "deploy")
      if (deploy.exists) {
        loadDeployJars(deploy)
      } else {
        log("[warning] No deploy dir found at " + deploy)
        Thread.currentThread.getContextClassLoader
      }
    } else {
      log("[warning] Akka home is not defined")
      Thread.currentThread.getContextClassLoader
    }
  }

  def loadDeployJars(deploy: File): ClassLoader = {
    val jars = deploy.listFiles.filter(_.getName.endsWith(".jar"))

    val nestedJars = jars flatMap { jar ⇒
      val jarFile = new JarFile(jar)
      val jarEntries = jarFile.entries.asScala.toArray.filter(_.getName.endsWith(".jar"))
      jarEntries map { entry ⇒ new File("jar:file:%s!/%s" format (jarFile.getName, entry.getName)) }
    }

    val urls = (jars ++ nestedJars) map { _.toURI.toURL }

    urls foreach { url ⇒ log("Deploying " + url) }

    new URLClassLoader(urls, Thread.currentThread.getContextClassLoader)
  }

  def addShutdownHook(bootables: Seq[Bootable]): Unit = {
    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run = {
        log("")
        log("Shutting down Akka...")

        for (bootable ← bootables) {
          log("Shutting down " + bootable.getClass.getName)
          bootable.shutdown()
        }

        log("Successfully shut down Akka")
      }
    }))
  }

  def banner = """
==============================================================================

                                                   ZZ:
                                                  ZZZZ
                                                 ZZZZZZ
                                                ZZZ' ZZZ
                                       ~7      7ZZ'   ZZZ
                                      :ZZZ:   IZZ'     ZZZ
                                     ,OZZZZ.~ZZ?        ZZZ
                                    ZZZZ' 'ZZZ$          ZZZ
                           .       $ZZZ   ~ZZ$            ZZZ
                         .=Z?.   .ZZZO   ~ZZ7              OZZ
                        .ZZZZ7..:ZZZ~   7ZZZ                ZZZ~
                      .$ZZZ$Z+.ZZZZ    ZZZ:                  ZZZ$
                   .,ZZZZ?'  =ZZO=   .OZZ                     'ZZZ
                 .$ZZZZ+   .ZZZZ    IZZZ                        ZZZ$
               .ZZZZZ'   .ZZZZ'   .ZZZ$                          ?ZZZ
            .ZZZZZZ'   .OZZZ?    ?ZZZ                             'ZZZ$
        .?ZZZZZZ'    .ZZZZ?    .ZZZ?                                'ZZZO
    .+ZZZZZZ?'    .7ZZZZ'    .ZZZZ                                    :ZZZZ
 .ZZZZZZ$'     .?ZZZZZ'   .~ZZZZ                                        'ZZZZ.


                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
                      NNNNN              $NNNN+
    =NNNNNNNNND$      NNNNN     DDDDDD:  $NNNN+     DDDDDN     NDDNNNNNNNN,
   NNNNNNNNNNNNND     NNNNN    DNNNNN    $NNNN+   8NNNNN=    :NNNNNNNNNNNNNN
  NNNNN$    DNNNNN    NNNNN  $NNNNN~     $NNNN+  NNNNNN      NNNNN,   :NNNNN+
   ?DN~      NNNNN    NNNNN MNNNNN       $NNNN+:NNNNN7        $ND      =NNNNN
            DNNNNN    NNNNNDNNNN$        $NNNNDNNNNN                  :DNNNNN
     ZNDNNNNNNNNND    NNNNNNNNNND,       $NNNNNNNNNNN           DNDNNNNNNNNNN
   NNNNNNNDDINNNNN    NNNNNNNNNNND       $NNNNNNNNNNND       ONNNNNNND8+NNNNN
 :NNNND      NNNNN    NNNNNN  DNNNN,     $NNNNNO 7NNNND     NNNNNO     :NNNNN
 DNNNN       NNNNN    NNNNN    DNNNN     $NNNN+   8NNNNN    NNNNN      $NNNNN
 DNNNNO     NNNNNN    NNNNN     NNNNN    $NNNN+    NNNNN$   NNNND,    ,NNNNND
  NNNNNNDDNNNNNNNN    NNNNN     =NNNNN   $NNNN+     DNNNN?  DNNNNNNDNNNNNNNND
   NNNNNNNNN  NNNN$   NNNNN      8NNNND  $NNNN+      NNNNN=  ,DNNNNNNND NNNNN$

==============================================================================
"""
}
