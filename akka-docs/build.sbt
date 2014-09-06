import akka.{ AkkaBuild, Dependencies, Formatting, SphinxDoc }
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.SbtSite.site
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.docFormatSettings

site.settings

site.sphinxSupport()

site.publishSite

SphinxDoc.sphinxPreprocessing

SphinxDoc.docsSettings

libraryDependencies ++= Dependencies.docs

publishArtifact in Compile := false

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test

MimaKeys.reportBinaryIssues := () // disable bin comp check
