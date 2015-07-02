import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

enablePlugins(JmhPlugin)
disablePlugins(Unidoc)

AkkaBuild.defaultSettings

AkkaBuild.dontPublishSettings
