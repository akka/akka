import akka.{ AkkaBuild, Formatting, OSGi, Unidoc, Dependencies }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.protobuf

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
