import com.lightbend.paradox.sbt.ParadoxPlugin
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import sbt.Keys._
import sbt._

object ParadoxBrowse extends AutoPlugin {

  object autoImport {
    val paradoxBrowse = taskKey[Unit]("Open the docs in the default browser")
  }
  import autoImport._

  override def trigger = noTrigger
  override def requires = ParadoxPlugin

  paradoxBrowse := {
    import java.awt.Desktop
    val rootDocFile = (target in (Compile, paradox)).value / "index.html"
    val log = streams.value.log
    if (!rootDocFile.exists()) log.info("No generated docs found, generate with the 'paradox' task")
    else if (Desktop.isDesktopSupported) Desktop.getDesktop.open(rootDocFile)
    else log.info(s"Couldn't open default browser, but docs are at $rootDocFile")
  }
}