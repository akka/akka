#!/usr/bin/env bash

alpn_version='2.0.5'

mkdir -p target

echo "Cloning h2spec..."
git clone https://github.com/summerwind/h2spec.git target/h2spec
cd target/h2spec
echo "Compiling h2spec, make sure you have Go (1.5) installed..."
make
for f in ls *zip; do unzip ${f}; done;

# since the tests need to start with the agent already present, downloading it lazily would result in much headache for people,
# thus it is currently comitted and available as file. we should eventually find a cleaner solution for this.
#cd ../..
#echo "Downloading jetty-alpn-agent..."
#rm -f jetty-alpn-agent-${alpn_version}.jar
#wget http://central.maven.org/maven2/org/mortbay/jetty/alpn/jetty-alpn-agent/${alpn_version}/jetty-alpn-agent-${alpn_version}.jar
