package akka

import sbt._
import Keys._
import java.io.File

object Publish {
  final val Snapshot = "-SNAPSHOT"

  lazy val settings = Seq(
    crossPaths := false,
    pomExtra := akkaPomExtra,
    publishTo := akkaPublishTo,
    credentials ++= akkaCredentials,
    organizationName := "Typesafe Inc.",
    organizationHomepage := Some(url("http://www.typesafe.com"))
  )

  lazy val versionSettings = Seq(
    commands += stampVersion
  )

  def akkaPomExtra = {
    <inceptionYear>2009</inceptionYear>
    <url>http://akka.io</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
  }

  def akkaPublishTo: Option[Resolver] = {
    val property = Option(System.getProperty("akka.publish.repository"))
    val repo = property map { "Akka Publish Repository" at _ }
    val m2repo = Path.userHome / ".m2" /"repository"
    repo orElse Some(Resolver.file("Local Maven Repository", m2repo))
  }

  def akkaCredentials: Seq[Credentials] = {
    val property = Option(System.getProperty("akka.publish.credentials"))
    property map (f => Credentials(new File(f))) toSeq
  }

  def stampVersion = Command.command("stamp-version") { state =>
    append((version in ThisBuild ~= stamp) :: Nil, state)
  }

  // TODO: replace with extracted.append when updated to sbt 0.10.1
  def append(settings: Seq[Setting[_]], state: State): State = {
    val extracted = Project.extract(state)
    import extracted._
    val append = Load.transformSettings(Load.projectScope(currentRef), currentRef.build, rootProject, settings)
    val newStructure = Load.reapply(session.original ++ append, structure)
    Project.setProject(session, newStructure, state)
  }

  def stamp(version: String): String = {
    if (version endsWith Snapshot) (version stripSuffix Snapshot) + "-" + timestamp(System.currentTimeMillis)
    else version
  }

  def timestamp(time: Long): String = {
    val format = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
    format.format(new java.util.Date(time))
  }
}
