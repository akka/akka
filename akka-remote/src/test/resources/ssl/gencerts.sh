#!/bin/bash

export PW=`cat password`

rm *.crt
rm *.p12
rm *.pem

./genca.sh

## some server certificates
./gen-one.example.com.sh
./gen-two.example.com.sh

./gen-island.example.com.sh

## a client certificate
./gen-client.example.com.sh

## a certificate valid for both server and client (peer-to-peer)
./gen-node.example.com.sh

