import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi, Protobuf }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterTools
Dependencies.clusterTools
Protobuf.settings

enablePlugins(akka.Unidoc, MultiNode, ScaladocNoVerificationOfDiagrams)
