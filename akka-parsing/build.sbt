import akka._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

AkkaBuild.defaultSettings
Formatting.docFormatSettings
site.settings
OSGi.parsing
Dependencies.parsing

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
scalacOptions += "-language:_"

// ScalaDoc doesn't like the macros
sources in doc in Compile := List()

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
