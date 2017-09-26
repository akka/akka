// import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc, OSGi } FIXME
import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi, Protobuf }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.distributedData
Dependencies.distributedData
Protobuf.settings

enablePlugins(MultiNodeScalaTest)
