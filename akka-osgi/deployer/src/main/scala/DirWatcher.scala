
package se.scalablesolutions.akka.osgi.deployer

import org.osgi.util.tracker.ServiceTracker
import java.io.File
import org.osgi.service.packageadmin.PackageAdmin
import org.osgi.framework.{Bundle, BundleContext}

class DirWatcher(context: BundleContext, bundlesDir: String, interval: Int) {

  private var running = false

  private final var timestamps = Map[String, Long]()

  private val packageAdminTracker = new ServiceTracker(context, classOf[PackageAdmin].getName, null)
  packageAdminTracker.open

  def startWatching {
    if (running) return
    running = true
    new Thread {
      override def run {
        try {
          while (running) {
            val found = getAllFiles(bundlesDir)
            analyseNewState(found)
            Thread.sleep(interval)
          }
        }
        catch {
          case e: InterruptedException =>
        }
      }
    }.start()
  }

  def stopWatching {
    running = false
  }

  private def getAllFiles(dirName: String): List[File] = {
    val content = new File(dirName).listFiles
    val files = content.filter(_.isFile).toList
    val childs = content.filter(_.isDirectory).toList.flatMap(d => getAllFiles(d.getCanonicalPath))
    files ::: childs
  }

  private def analyseNewState(found: List[File]) {
    println("FOUND:" + found)

    // new or updated
    val changed = found.filter(f => timestamps.getOrElse(f.getCanonicalPath, -1L) < f.lastModified)
    changed.foreach {f =>
      val name = f.getCanonicalPath
      timestamps += (name -> f.lastModified)
      if (name.endsWith(".jar")) installOrUpdateBundle(name)
    }
    println("CHANGED:" + changed)

    // removed
    val removed = timestamps.filter(f => !found.map(_.getCanonicalPath).contains(f._1))
    removed.foreach {f =>
      context.getBundles.filter(b => b.getLocation.equals("file:" + f._1)).foreach(_.uninstall)
      timestamps -= f._1
    }
    println("REMOVED:" + removed)

    if (changed.size + removed.size > 0)
      startAllAndRefresh()

    println("")
  }

  private def startAllAndRefresh() {
    context.getBundles.filter(b => b.getState != Bundle.ACTIVE && !isFragment(b)).foreach {b =>
      try {
        b.start
      } catch {
        case e: Exception => {
          System.out.println("Problems starting bundle: " + b)
          e.printStackTrace
        }
      }
    }

    val admin = this.packageAdminTracker.getService.asInstanceOf[PackageAdmin]
    System.out.println("DirInstaller: Refreshing packages")
    admin.refreshPackages(null)
  }


  private def isFragment(b: Bundle): Boolean = {
    var admin: PackageAdmin = this.packageAdminTracker.getService.asInstanceOf[PackageAdmin]
    return admin.getBundleType(b) == PackageAdmin.BUNDLE_TYPE_FRAGMENT
  }


  private def installOrUpdateBundle(s: String) {
    for (b <- context.getBundles) {
      if (b.getLocation.endsWith(s)) {
        System.out.println("DirInstaller: Updating bundle [" + b.getSymbolicName + "]")
        b.stop
        b.update
        return
      }
    }
    System.out.println("DirInstaller: Installing bundle [" + s + "]")
    context.installBundle("file:" + s)
  }


}