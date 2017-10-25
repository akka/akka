import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi, Protobuf }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterSharding
Dependencies.clusterSharding
Protobuf.settings

enablePlugins(akka.Unidoc, MultiNode, ScaladocNoVerificationOfDiagrams)
