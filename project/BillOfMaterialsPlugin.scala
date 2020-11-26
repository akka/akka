/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka

import scala.xml.Elem

import sbt.AutoPlugin
import sbt.PluginTrigger
import sbt.ProjectReference
import sbt.Keys._
import sbt._

/**
 * Plugin to create a Maven Bill of Materials (BOM) pom.xml
 *
 * - https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#bill-of-materials-bom-poms
 * - https://howtodoinjava.com/maven/maven-bom-bill-of-materials-dependency/
 */
object BillOfMaterialsPlugin extends AutoPlugin {

  override def trigger = PluginTrigger.NoTrigger

  object autoImport {
    val bomIncludeProjects =
      settingKey[Seq[ProjectReference]]("the list of projects to include in the Bill of Materials pom.xml")
    val bomDependenciesListing =
      settingKey[Elem]("the generated `<dependencyManagement>` section to be added to `sbt.pomExtra`")
  }
  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      // publish Maven Style
      publishMavenStyle := true,
      // this setting removes the scala bin version from the artifact name (make sure it is not overwritten)
      crossVersion := CrossVersion.disabled,
      autoScalaLibrary := false,
      bomDependenciesListing := {
          val dependencies =
            Def.settingDyn {
              (bomIncludeProjects.value).map {
                project =>
                  Def.setting {
                    val artifactName = (project / artifact).value.name
                    val crossBuild = (project / crossVersion).value
                    if (crossBuild == CrossVersion.disabled) {
                      toXml(artifactName, (project / organization).value, (project / version).value)
                    } else if (crossBuild == CrossVersion.binary) {
                      (project / crossScalaVersions).value.map { scalaVersion =>
                        val crossFunc =
                          CrossVersion(Binary(), scalaVersion, CrossVersion.binaryScalaVersion(scalaVersion)).get
                        // convert artifactName to match the desired scala version
                        val artifactId = crossFunc(artifactName);
                        toXml(artifactId, (project / organization).value, (project / version).value)
                      }
                    } else {
                      throw new RuntimeException(s"Support for `crossVersion := $crossBuild` is not implemented")
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
        },
      pomExtra := (pomExtra.value) :+ bomDependenciesListing.value
    ) ++
      // This disables creating jar, source jar and javadocs, and will cause the packaging type to be "pom" when the pom is created
      Classpaths.defaultPackageKeys.map(_ / publishArtifact := false)

  private def toXml(artifactId: String, organization: String, version: String): Elem = {
    <dependency>
      <groupId>{organization}</groupId>
      <artifactId>{artifactId}</artifactId>
      <version>{version}</version>
    </dependency>
  }

}
