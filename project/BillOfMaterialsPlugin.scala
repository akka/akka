/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import scala.xml.Comment
import scala.xml.Elem
import scala.xml.Node

import sbt.AutoPlugin
import sbt.Keys._
import sbt.PluginTrigger
import sbt.ProjectReference
import sbt._

/**
 * Plugin to create a Maven Bill of Materials (BOM) pom.xml
 *
 * Set `crossVersion := CrossVersion.disabled` to create a single BOM with all Scala binary versions.
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
      autoScalaLibrary := false,
      bomDependenciesListing := {
        val dependencies =
          Def.settingDyn {
            val mutipleScalaVersionsInBom = crossVersion.value == CrossVersion.disabled
            val desiredScalaVersion = scalaVersion.value
            (bomIncludeProjects.value).map {
              project =>
                Def.setting {
                  val artifactName = (project / artifact).value.name
                  val org = (project / organization).value
                  val ver = (project / version).value
                  val crossBuild = (project / crossVersion).value
                  if (crossBuild == CrossVersion.disabled) {
                    toXml(artifactName, org, ver)
                  } else if (crossBuild == CrossVersion.binary) {
                    if (mutipleScalaVersionsInBom) {
                      (project / crossScalaVersions).value.map { scalaV =>
                        toXmlScalaBinary(artifactName, org, ver, scalaV, desiredScalaVersion)
                      }
                    } else {
                      toXmlScalaBinary(artifactName, org, ver, (project / scalaVersion).value, desiredScalaVersion)
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

  private def toXmlScalaBinary(artifactName: String, organization: String, version: String, scalaVersion: String, desiredScalaVersion: String): Node = {
    if (scalaVersion == desiredScalaVersion) {
      val crossFunc = CrossVersion(Binary(), scalaVersion, CrossVersion.binaryScalaVersion(scalaVersion)).get
      // convert artifactName to match the desired scala version
      val artifactId = crossFunc(artifactName)
      toXml(artifactId, organization, version)
    } else Comment(s" $artifactName is not available for $desiredScalaVersion ")
  }

  private def toXml(artifactId: String, organization: String, version: String): Node = {
    <dependency>
      <groupId>{organization}</groupId>
      <artifactId>{artifactId}</artifactId>
      <version>{version}</version>
    </dependency>
  }

}
