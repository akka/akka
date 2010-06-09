 /*---------------------------------------------------------------------------\
| Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se> |
\---------------------------------------------------------------------------*/

import sbt._
import sbt.CompileOrder._
import spde._

import java.util.jar.Attributes
import java.util.jar.Attributes.Name._
import java.io.File

import com.weiglewilczek.bnd4sbt._

trait AkkaWrappersProject extends DefaultProject {

  // ------------------------------------------------------------
  // project versions
  val JERSEY_VERSION:String 
  val ATMO_VERSION:String 
  val CASSANDRA_VERSION:String 
  val LIFT_VERSION:String 
  val SCALATEST_VERSION:String 
  val MULTIVERSE_VERSION:String 

  // OSGi wrappers
  lazy val akka_wrappers = project("akka-wrap", "akka-wrap", new AkkaWrappersParentProject(_))

  import Process._
  lazy val publishLocalMvnWrapped = runMvnInstallWrapped
  def runMvnInstallWrapped = task {
    for (absPath <- wrappedArtifacts.getPaths) {
      val artifactRE = """.*/([^/]+)-([^-]+).jar""".r
      val artifactRE(artifactId, artifactVersion) = absPath
      val command = "mvn install:install-file" +
            " -Dfile=" + absPath +
            " -DgroupId=se.scalablesolutions.akka.akka-wrap" +
            " -DartifactId=" + artifactId +
            " -Dversion=" + artifactVersion +
            " -Dpackaging=jar -DgeneratePom=true"
      command ! log
    }
    None
  } dependsOn(`package`) describedAs("Run mvn install for wrapped artifacts in akka-wrap.")

  // ================= OSGi Wrappers ==================
  class JgroupsWrapperProject(info: ProjectInfo) extends OSGiWrapperProject(info) {
    override def wrappedVersion = "2.9.0.GA"
    override def bndImportPackage = Set("org.testng.*;resolution:=optional",
      "org.bouncycastle.jce.provider;resolution:=optional",
      "bsh;resolution:=optional",
      "*")
    
    val jgroups = "jgroups" % "jgroups" % wrappedVersion % "compile"
  }

  class DispatchJsonWrapperProject(info: ProjectInfo) extends OSGiWrapperProject(info) {
    override def wrappedVersion = "0.7.4"
    
    val dispatch_json = "net.databinder" % "dispatch-json_2.8.0.RC3" % wrappedVersion % "compile"
  }

  class AkkaWrappersParentProject(info: ProjectInfo) extends ParentProject(info) {
    lazy val jgroups_wrapper = project("jgroups-wrapper", "jgroups-wrapper",
      new JgroupsWrapperProject(_))
    lazy val dispath_json = project("dispatch-json", "dispatch-json",
      new DispatchJsonWrapperProject(_))
  }

  def wrappedArtifacts = descendents(info.projectPath / "akka-wrap", "*" + buildScalaVersion  + "_osgi-" + "*.jar")

  abstract class OSGiWrapperProject(info: ProjectInfo) extends DefaultProject(info) with BNDPlugin {
    def wrappedVersion:String 
    override def version = OpaqueVersion(wrappedVersion)
    override def artifactID = moduleID + "_osgi"
    override def bndEmbedDependencies = true
    override def bndExportPackage = Set("*")
  }


}
