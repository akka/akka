
package se.scalablesolutions.akka.osgi.deployer

import org.osgi.framework.{BundleContext, BundleActivator}
import java.io.File

class Activator extends BundleActivator {

  private val AKKA_DEPLOYER_DIR = "akka.deployer.dir"
  private val AKKA_DEPLOYER_POLL = "akka.deployer.poll"
  
  private var watcher: DirWatcher = _

  def start(context: BundleContext) {
    var bundlesDir = context.getProperty(AKKA_DEPLOYER_DIR)
    bundlesDir = if (bundlesDir == null) "akka" else bundlesDir

    // check dir exists
    if (new File(bundlesDir).listFiles == null) {
      System.out.println("DirInstaller WARNING: Directory '" + bundlesDir + "' does not exist!")
      return
    }

    var interval = context.getProperty(AKKA_DEPLOYER_POLL)
    interval = if (interval == null) "2000" else interval

    watcher = new DirWatcher(context, bundlesDir, interval.toInt)
    watcher.startWatching
  }

  def stop(context: BundleContext) {
    if (watcher != null)
      watcher.stopWatching
  }

}
