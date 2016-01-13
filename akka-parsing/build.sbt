import akka.{ AkkaBuild, Dependencies, Formatting, SphinxDoc }
import akka.ValidatePullRequest._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.site.SphinxSupport._
import com.typesafe.tools.mima.plugin.MimaKeys

enablePlugins(ScaladocNoVerificationOfDiagrams)

AkkaBuild.defaultSettings

Formatting.docFormatSettings

site.settings

Dependencies.parsing

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test

AkkaBuild.dontPublishSettings
