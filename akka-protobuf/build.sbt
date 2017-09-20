import akka.{ AkkaBuild, Formatting, OSGi, Dependencies }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.protobuf

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
