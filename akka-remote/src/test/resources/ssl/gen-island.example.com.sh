#!/bin/bash

export PW=`cat password`

# Create a server certificate, tied to island.example.com
keytool -genkeypair -v \
  -alias island.example.com \
  -dname "CN=island.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore island.example.com.p12 \
  -storetype PKCS12 \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg EC \
  -keysize 256 \
  -validity 3650

# Create a certificate signing request for island.example.com
keytool -certreq -v \
  -alias island.example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore island.example.com.p12 \
  -file island.example.com.csr

# Tell exampleCA to sign the island.example.com certificate.
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile island.example.com.csr \
  -outfile island.example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -rfc \
  -validity 3650

## This certificate has no SAN! ^^^^

rm island.example.com.csr

# Tell island.example.com.p12 it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore island.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into island.example.com.p12
keytool -import -v \
  -alias island.example.com \
  -file island.example.com.crt \
  -keystore island.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW
