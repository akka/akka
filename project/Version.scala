/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._

/**
 * Generate version.conf and akka/Version.scala files based on the version setting.
 */
object Version {

  def versionSettings: Seq[Setting[_]] = inConfig(Compile)(Seq(
    resourceGenerators <+= generateVersion(resourceManaged, _ / "akka-http-version.conf",
      """|akka.http.version = "%s"
         |"""),
    sourceGenerators <+= generateVersion(sourceManaged, _ / "akka" / "http" / "Version.scala",
      """|package akka.http
         |
         |import com.typesafe.config.Config
         |
         |object Version {
         |  val current: String = "%s"
         |  def check(config: Config): Unit = {
         |    val configVersion = config.getString("akka.http.version")
         |    if (configVersion != current) {
         |      throw new akka.ConfigurationException(
         |        "Akka JAR version [" + current + "] does not match the provided " +
         |        "config version [" + configVersion + "]")
         |    }
         |  }
         |}
         |""")
  ))

  def generateVersion(dir: SettingKey[File], locate: File => File, template: String) = Def.task[Seq[File]] {
    val file = locate(dir.value)
    val content = template.stripMargin.format(version.value)
    if (!file.exists || IO.read(file) != content) IO.write(file, content)
    Seq(file)
  }

}
