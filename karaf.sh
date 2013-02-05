#!/bin/bash
cd ..
#Karaf download
if [ ! -f apache-karaf-2.3.0 ];
then
echo "karaf download"
wget -q http://mirror.switch.ch/mirror/apache/dist/karaf/2.3.0/apache-karaf-2.3.0.tar.gz -O karaf.tar.gz
echo "karaf untar"
tar xf karaf.tar.gz
rm karaf.tar.gz
fi
deploy_directory=apache-karaf-2.3.0/deploy
#Bundles download
wget -q "http://repo1.maven.org/maven2/com/typesafe/akka/akka-actor_2.10/2.1.0/akka-actor_2.10-2.1.0.jar" -O $deploy_directory/akka-actor-2.1.jar
wget -q      "http://repo1.maven.org/maven2/com/typesafe/akka/akka-osgi_2.10/2.1.0/akka-osgi_2.10-2.1.0.jar" -O $deploy_directory/akka-osgi-2.1.jar
wget -q     "http://repo1.maven.org/maven2/com/typesafe/akka/akka-remote_2.10/2.1.0/akka-remote_2.10-2.1.0.jar" -O $deploy_directory/akka-remote-2.1.jar
wget -q     "http://repo1.maven.org/maven2/io/netty/netty/3.5.7.Final/netty-3.5.7.Final.jar" -O $deploy_directory/netty-3.5.7.jar
wget -q     "http://repo1.maven.org/maven2/com/typesafe/akka/akka-cluster-experimental_2.10/2.1.0/akka-cluster-experimental_2.10-2.1.0.jar" -O $deploy_directory/akka-cluster-2.1.jar
wget -q     "http://repo1.maven.org/maven2/com/typesafe/config/1.0.0/config-1.0.0.jar" -O $deploy_directory/config-1.0.jar
wget -q     "http://repo1.maven.org/maven2/org/scala-lang/scala-library/2.10.0/scala-library-2.10.0.jar" -O $deploy_directory/scala-library-2.10.jar
echo $PWD
cp bundles/*.jar $deploy_directory

echo please run apache-karaf-2.3.0/bin/karaf after having replace, in $deploy_directory, legacy bundles by the bundles you wand to test
