/**
  * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
  */

package akka

import com.lightbend.sbt.JavaFormatterPlugin.JavaFormatterKeys._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import sbt._

object Formatting {

  import sbt.Keys._
  import sbt.io._
  import scalariform.formatter.preferences._

  lazy val formatSettings = Seq(
    ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Compile := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Test := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in MultiJvm := setPreferences(ScalariformKeys.preferences.value),
    //below is for sbt java formatter
    (excludeFilter in format) := {
      val ignoredPackages = List(
        "akka.dispatch.forkjoin",
        "akka.protobuf",
        "sun.reflect",
      )

      val ignoredFiles = {
        val javaSourceBase = (javaSource in Compile).value
        ignoredPackages.map(_.split('.'))
          .map(_.foldLeft(javaSourceBase)((parent, subPath) => parent / subPath))
          .flatMap(file => Path.allSubpaths(file).map(_._1))
      }.toSet
      new SimpleFileFilter(file => {
        val ignored = ignoredFiles.contains(file)
        if (ignored) {
          println("ignored :" + file)
        }
        ignored
      })
    }
  )

  def setPreferences(preferences: IFormattingPreferences) = preferences
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, false)
    .setPreference(DoubleIndentMethodDeclaration, false)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(NewlineAtEndOfFile, true)
}
