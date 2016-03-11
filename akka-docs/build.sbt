import akka.{ AkkaBuild, Dependencies, Formatting, SphinxDoc }
import akka.ValidatePullRequest._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.site.SphinxSupport._

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.docFormatSettings
Dependencies.docs

site.settings
site.sphinxSupport()
site.publishSite

SphinxDoc.sphinxPreprocessing
SphinxDoc.docsSettings

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
additionalTasks in ValidatePR += generate in Sphinx

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
