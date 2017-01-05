#!/usr/bin/env bash

h2spec_version='1.5.0'

mkdir -p target
mkdir -p target/h2spec

#echo "Cloning h2spec..."
#git clone https://github.com/summerwind/h2spec.git target/h2spec
#cd target/h2spec
#echo "Compiling h2spec, make sure you have Go (1.5) installed..."
#make
#for f in $(ls *zip); do unzip ${f}; done;

cd target/h2spec

echo "Downloading h2spec (for all platforms)..."
wget https://github.com/summerwind/h2spec/releases/download/v${h2spec_version}/h2spec_darwin_amd64.zip
unzip h2spec_darwin_amd64.zip
wget https://github.com/summerwind/h2spec/releases/download/v${h2spec_version}/h2spec_linux_amd64.zip
unzip h2spec_linux_amd64.zip
wget https://github.com/summerwind/h2spec/releases/download/v${h2spec_version}/h2spec_windows_amd64.zip
unzip h2spec_windows_amd64.zip
