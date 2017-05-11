import akka.{ AkkaBuild, Dependencies, Formatting }
import akka.ValidatePullRequest._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.docFormatSettings
Dependencies.docs

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
additionalTasks in ValidatePR += paradox

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
enablePlugins(ParadoxPlugin)

paradoxProperties ++= Map(
  "extref.wikipedia.base_url" -> "https://en.wikipedia.org/wiki/%s",
  "scala.version" -> scalaVersion.value,
  "akka.version" -> version.value,
  "snip.code.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
  "snip.akka.base_dir" -> ((baseDirectory in Test).value / "..").getAbsolutePath
)

resolvers += Resolver.bintrayRepo("2m", "maven")
paradoxTheme := Some("com.lightbend.akka" % "paradox-theme-akka" % "b74885b8+20170511-1711")
paradoxNavigationDepth := 1
paradoxNavigationExpandDepth := Some(1)
paradoxNavigationIncludeHeaders := true
