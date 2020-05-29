#!/bin/bash

export PW=`cat password`

# Create a server certificate, tied to two.example.com
keytool -genkeypair -v \
  -alias two.example.com \
  -dname "CN=two.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
  -keystore two.example.com.p12 \
  -storetype PKCS12 \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg EC \
  -keysize 256 \
  -validity 3650

# Create a certificate signing request for two.example.com
keytool -certreq -v \
  -alias two.example.com \
  -keypass:env PW \
  -storepass:env PW \
  -keystore two.example.com.p12 \
  -file two.example.com.csr

# Tell exampleCA to sign the two.example.com certificate.
keytool -gencert -v \
  -alias exampleca \
  -keypass:env PW \
  -storepass:env PW \
  -keystore exampleca.p12 \
  -infile two.example.com.csr \
  -outfile two.example.com.crt \
  -ext KeyUsage:critical="digitalSignature,keyEncipherment" \
  -ext EKU="serverAuth" \
  -ext SAN="DNS:two.example.com,DNS:number-two.example.com,DNS:example.com" \
  -rfc \
  -validity 3650

rm two.example.com.csr

# Tell two.example.com.p12 it can trust exampleca as a signer.
keytool -import -v \
  -alias exampleca \
  -file exampleca.crt \
  -keystore two.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW << EOF
yes
EOF

# Import the signed certificate back into two.example.com.p12
keytool -import -v \
  -alias two.example.com \
  -file two.example.com.crt \
  -keystore two.example.com.p12 \
  -storetype PKCS12 \
  -storepass:env PW
