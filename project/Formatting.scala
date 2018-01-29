/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

object Formatting {
  import scalariform.formatter.preferences._

  lazy val formatSettings = Seq(
    ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Compile := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Test := setPreferences(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in MultiJvm := setPreferences(ScalariformKeys.preferences.value)
  )

  lazy val docFormatSettings = Seq(
    ScalariformKeys.preferences := setPreferences(ScalariformKeys.preferences.value, rewriteArrowSymbols = false),
    ScalariformKeys.preferences in Compile := setPreferences(ScalariformKeys.preferences.value, rewriteArrowSymbols = false),
    ScalariformKeys.preferences in Test := setPreferences(ScalariformKeys.preferences.value, rewriteArrowSymbols = false),
    ScalariformKeys.preferences in MultiJvm := setPreferences(ScalariformKeys.preferences.value, rewriteArrowSymbols = false)
  )

  def setPreferences(preferences: IFormattingPreferences, rewriteArrowSymbols: Boolean = true) = preferences
    .setPreference(RewriteArrowSymbols, rewriteArrowSymbols)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, false)
    .setPreference(DoubleIndentMethodDeclaration, false)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(NewlineAtEndOfFile, true)
}
