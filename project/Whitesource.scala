import sbt._
import sbt.Keys._
import sbtwhitesource.WhiteSourcePlugin.autoImport._
import sbtwhitesource._
import com.typesafe.sbt.SbtGit.GitKeys._

object Whitesource extends AutoPlugin {
  override def requires = WhiteSourcePlugin

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    // do not change the value of whitesourceProduct
    whitesourceProduct := "Lightbend Reactive Platform",
    whitesourceAggregateProjectName := {
      val projectName = (moduleName in LocalRootProject).value.replace("-root", "")
      projectName + "-" + (
        if (isSnapshot.value)
          if (gitCurrentBranch.value == "master") "master"
          else "adhoc"
        else "stable"
      )
    }
  )
}
