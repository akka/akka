/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

import scala.xml.Elem

import sbt.AutoPlugin
import sbt.PluginTrigger
import sbt.ProjectReference
import sbt._

object BillOfMaterialsPlugin extends AutoPlugin {

  override def trigger = PluginTrigger.NoTrigger

  object autoImport {
    val includedProjects = settingKey[Seq[ProjectReference]]("")
//    val fixedScalaVersion = settingKey[String]("")
  }
  import autoImport._
  import sbt.Keys._
  import sbt._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      // publish Maven Style
      publishMavenStyle := true,
      // Produce a single BOM with all the artifacts
      crossVersion := CrossVersion.disabled, // this setting removes the scala bin version from the artifact name
//      fixedScalaVersion := crossScalaVersions.value.head,
//      crossScalaVersions := Seq(fixedScalaVersion.value),
//      scalaVersion := fixedScalaVersion.value,
      crossPaths := false,
      autoScalaLibrary := false,
      pomExtra := (pomExtra.value) :+ {
          val dependencies =
            Def.settingDyn {
              (includedProjects.value).map {
                project =>
                  Def.setting {
                    val artifactName = (project / artifact).value.name
                    if ((project / crossPaths).value) {
                      (crossScalaVersions in project).value.map { supportedVersion =>
                        // we are sure this won't be a None
                        val crossFunc =
                          CrossVersion(Binary(), supportedVersion, CrossVersion.binaryScalaVersion(supportedVersion)).get
                        // convert artifactName to match the desired scala version
                        val artifactId = crossFunc(artifactName);
                        toXml(artifactId, (project / organization).value, (project / version).value)
                      }
                    } else {
                      toXml(artifactName, (project / organization).value, (project / version).value)
                    }
                  }
              }.join
            }.value

          // format: off
          <dependencyManagement>
            <dependencies>
              {dependencies}
            </dependencies>
          </dependencyManagement>
          // format:on
        }
    // This disables creating jar, source jar and javadocs, and will cause the packaging type to be "pom" when the
    // pom is created
    ) ++ Classpaths.defaultPackageKeys.map(key => publishArtifact in key := false)

  private def toXml(artifactId: String, organization: String, version: String): Elem = {
    <dependency>
      <groupId>{organization}</groupId>
      <artifactId>{artifactId}</artifactId>
      <version>{version}</version>
    </dependency>
  }

}
