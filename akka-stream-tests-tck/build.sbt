import akka._

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
Dependencies.streamTestsTck

disablePlugins(MimaPlugin)

// These TCK tests are using System.gc(), which
// is causing long GC pauses when running with G1 on
// the CI build servers. Therefore we fork these tests
// to run with small heap without G1. 
fork in Test := true
