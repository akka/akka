#!/bin/bash

export PW=`cat password`

# Create a server certificate, tied to one.example.com
keytool -genkeypair -v \
  -alias one.example.com \
  -dname "CN=one.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore one.example.com.p12 \
  -storetype PKCS12 \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg EC \
  -keysize 256 \
  -validity 3650

# Create a certificate signing request for one.example.com
keytool -certreq -v \
  -alias one.example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore one.example.com.p12 \
  -file one.example.com.csr

# Tell exampleCA to sign the one.example.com certificate.
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile one.example.com.csr \
  -outfile one.example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:one.example.com,DNS:number-one.example.com,DNS:example.com" \
  -rfc \
  -validity 3650

rm one.example.com.csr

# Tell one.example.com.p12 it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore one.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

keytool -import -v \
  -alias one.example.com \
  -file one.example.com.crt \
  -keystore one.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW
