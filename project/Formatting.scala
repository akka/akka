/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

import scalariform.formatter.preferences._

object Formatting {
  lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := configureFormatting(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Test     := configureFormatting(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in MultiJvm := configureFormatting(ScalariformKeys.preferences.value)
  )

  lazy val docFormatSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences in Compile  := configureFormattingInDocs(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in Test     := configureFormattingInDocs(ScalariformKeys.preferences.value),
    ScalariformKeys.preferences in MultiJvm := configureFormattingInDocs(ScalariformKeys.preferences.value)
  )

  def configureFormatting(prefs: IFormattingPreferences) =
    prefs
      .setPreference(RewriteArrowSymbols, true)
      .setPreference(AlignParameters, false)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(SpacesAroundMultiImports, true)
      .setPreference(DanglingCloseParenthesis, Prevent)

  def configureFormattingInDocs(prefs: IFormattingPreferences) =
    prefs
      .setPreference(RewriteArrowSymbols, false)
      .setPreference(AlignParameters, false)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(SpacesAroundMultiImports, true)
      .setPreference(DanglingCloseParenthesis, Prevent)
}
