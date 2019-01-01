/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.CopyrightHeader
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{HeaderFileType, headerMappings, headerSources}
import sbt.Keys.sourceDirectory
import sbt.{Compile, Def, Test, inConfig, _}

object CopyrightHeaderForProtobuf extends CopyrightHeader {
  override protected def headerMappingSettings: Seq[Def.Setting[_]] = {
    super.headerMappingSettings
    Seq(Compile, Test).flatMap { config =>
      inConfig(config) {
        Seq(
          headerSources in config ++=
            (((sourceDirectory in config).value / "protobuf") ** "*.proto").get,
          headerMappings := headerMappings.value ++ Map(
            HeaderFileType("proto") -> cStyleComment
          )
        )
      }
    }
  }
}
