package akka

import sbt._
import sbt.Keys._
import sbt.Project.Initialize
import java.io.File

object Publish {
  final val Snapshot = "-SNAPSHOT"

  val defaultPublishTo = SettingKey[File]("default-publish-to")

  lazy val settings = Seq(
    crossPaths := false,
    pomExtra := akkaPomExtra,
    publishTo <<= akkaPublishTo,
    credentials ++= akkaCredentials,
    organizationName := "Typesafe Inc.",
    organizationHomepage := Some(url("http://www.typesafe.com")),
    publishMavenStyle := true,
    // Maven central cannot allow other repos.  
    // TODO - Make sure all artifacts are on central.
    pomIncludeRepository := { x => false }
  )

  lazy val versionSettings = Seq(
    commands += stampVersion
  )

  def akkaPomExtra = {
    (<inceptionYear>2009</inceptionYear>
    <url>http://akka.io</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git://github.com/akka/akka.git</url>
      <connection>scm:git:git@github.com:akka/akka.git</connection>
    </scm>) ++ makeDevelopersXml(Map(
        "jboner" -> "Jonas Boner",
        "viktorklang" -> "Viktor Klang",
        "rkuhn" -> "Roland Kuhn",
        "pvlugter" -> "Peter Vlugter"
        // TODO - More than the names in the last 10 commits
      ))
  }


  private[this] def makeDevelopersXml(users: Map[String,String]) =
    <developers>
      { 
        for((id, user) <- users)
        yield <developer><id>{id}</id><name>{user}</name></developer>
      }
    </developers>

  def sonatypePublishTo: Initialize[Option[Resolver]] = {
    version { v: String =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
      else                             Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }
  }

  def akkaPublishTo: Initialize[Option[Resolver]] = {
    (defaultPublishTo, version) { (default, v) =>
      akkaPublishRepository orElse
      sonatypeRepo(v) orElse
      Some(Resolver.file("Default Local Repository", default))
    }
  }

  def akkaPluginPublishTo: Initialize[Option[Resolver]] = {
    (defaultPublishTo, version) { (default, version) =>
      akkaPublishRepository orElse
      pluginRepo(version) orElse
      Some(Resolver.file("Default Local Repository", default))
    }
  }

  def sonatypeRepo(version: String): Option[Resolver] = {
    Option(sys.props("publish.maven.central")) filter (_.toLowerCase == "true") map { _ =>
      val nexus = "https://oss.sonatype.org/"
      if(version endsWith "-SNAPSHOT") ("snapshots" at nexus + "content/repositories/snapshots")
      else ("releases"  at nexus + "service/local/staging/deploy/maven2")
    }
  }

  def pluginRepo(version: String): Option[Resolver] =
    Option(sys.props("publish.maven.central")) collect { case mc if mc.toLowerCase == "true" =>
      val name = if (version endsWith "-SNAPSHOT") "sbt-plugin-snapshots" else "sbt-plugin-releases"
      Resolver.url(name, url("http://scalasbt.artifactoryonline.com/scalasbt/" + name))(Resolver.ivyStylePatterns)
    }

  def akkaPublishRepository: Option[Resolver] =
      Option(System.getProperty("akka.publish.repository", null)) map { "Akka Publish Repository" at _ }

  def akkaCredentials: Seq[Credentials] =
    Option(System.getProperty("akka.publish.credentials", null)) map (f => Credentials(new File(f))) toSeq

  // timestamped versions

  def stampVersion = Command.command("stamp-version") { state =>
    val extracted = Project.extract(state)
    extracted.append(List(version in ThisBuild ~= stamp), state)
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
