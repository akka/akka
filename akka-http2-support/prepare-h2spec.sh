#!/usr/bin/env bash

declare alpn_version='2.0.5'

mkdir -p target

echo "Cloning h2spec..."
git clone https://github.com/summerwind/h2spec.git target/h2spec
cd target/h2spec
echo "Compiling h2spec, make sure you have Go (1.5) installed..."
make
for f in ls *zip; do unzip ${f}; done;

echo "Downloading jetty-alpn-agent..."
wget http://central.maven.org/maven2/org/mortbay/jetty/alpn/jetty-alpn-agent/${alpn_version}/jetty-alpn-agent-${alpn_version}.jar
