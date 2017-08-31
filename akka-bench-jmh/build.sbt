import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

// enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams) // FIXME
enablePlugins(JmhPlugin)
// disablePlugins(Unidoc, MimaPlugin) // FIXME unidoc
disablePlugins(MimaPlugin)

AkkaBuild.defaultSettings

AkkaBuild.dontPublishSettings
AkkaBuild.dontPublishDocsSettings
Dependencies.benchJmh
