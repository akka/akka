/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.headerSources
import sbt.Keys.sourceDirectory
import sbt.{Compile, Def, Test, _}


object CopyrightHeaderForJdk9 extends CopyrightHeader {

  override protected def headerMappingSettings: Seq[Def.Setting[_]] = {
    super.headerMappingSettings
    import Jdk9._
    Seq(
      headerSources in Compile ++=
        (((sourceDirectory in Compile).value / SCALA_SOURCE_DIRECTORY) ** "*.scala").get,
      headerSources in Test ++=
        (((sourceDirectory in Test).value / SCALA_TEST_SOURCE_DIRECTORY) ** "*.scala").get,
      headerSources in Compile ++=
        (((sourceDirectory in Compile).value / JAVA_SOURCE_DIRECTORY) ** "*.java").get,
      headerSources in Test ++=
        (((sourceDirectory in Test).value / JAVA_TEST_SOURCE_DIRECTORY) ** "*.java").get,
    )
  }
}
