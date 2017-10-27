import sbt._
import sbt.Keys._
import sbtwhitesource.WhiteSourcePlugin.autoImport._
import sbtwhitesource._
import com.typesafe.sbt.SbtGit.GitKeys._

object Whitesource extends AutoPlugin {
  override def requires = WhiteSourcePlugin

  override def trigger = allRequirements

  def majorMinor(version: String): Option[String] = """\d+\.\d+""".r.findFirstIn(version)

  override lazy val projectSettings = Seq(
    // do not change the value of whitesourceProduct
    whitesourceProduct := "Lightbend Reactive Platform",
    whitesourceFailOnError := true,
    whitesourceAggregateProjectName := {
      val projectName = (moduleName in LocalRootProject).value.replace("-root", "")
      projectName + "-" + (
        if (isSnapshot.value)
          if (gitCurrentBranch.value == "master") "master"
          else "adhoc"
        else majorMinor((version in LocalRootProject).value).map(_ + "-stable").getOrElse("adhoc")
      )
    }
  )
}
