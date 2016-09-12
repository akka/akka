/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object Formatting {
  lazy val formatSettings = Seq(
    ScalariformKeys.preferences in Compile  <<= formattingPreferences,
    ScalariformKeys.preferences in Test     <<= formattingPreferences,
    ScalariformKeys.preferences in MultiJvm <<= formattingPreferences
  )

  lazy val docFormatSettings = Seq(
    ScalariformKeys.preferences in Compile  <<= docFormattingPreferences,
    ScalariformKeys.preferences in Test     <<= docFormattingPreferences,
    ScalariformKeys.preferences in MultiJvm <<= docFormattingPreferences
  )

  def formattingPreferences = Def.setting {
    import scalariform.formatter.preferences._
    ScalariformKeys.preferences.value
      .setPreference(RewriteArrowSymbols, true)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
      .setPreference(DoubleIndentClassDeclaration, false)
  }

  def docFormattingPreferences = Def.setting {
    import scalariform.formatter.preferences._
    ScalariformKeys.preferences.value
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
      .setPreference(DoubleIndentClassDeclaration, false)
  }
}
