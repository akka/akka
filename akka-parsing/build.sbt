import akka._
import com.typesafe.tools.mima.plugin.MimaKeys

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
