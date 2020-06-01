#!/bin/bash

function createKeySet() {
  PREFIX=$1
  KEYALG=$2
  KEYLENGTH=$3
  KeyUsage=$4
  ExtendedKeyUsage=$5
  SubjectAlternativeNames=$6

  # Create a certificate used for peer-to-peer, under the example.com CA
  keytool -genkeypair -v \
    -alias $PREFIX.example.com \
    -dname "CN=$PREFIX.example.com, OU=Example Org, O=Example Company, L=San Francisco, ST=California, C=US" \
    -keystore $PREFIX.example.com.p12 \
    -storetype PKCS12 \
    -keypass:env PW \
    -storepass:env PW \
    -storetype PKCS12 \
    -keyalg $KEYALG \
    -keysize $KEYLENGTH \
    -validity 3650

  # Create a certificate signing request for $PREFIX.example.com
  keytool -certreq -v \
    -alias $PREFIX.example.com \
    -keypass:env PW \
    -storepass:env PW \
    -keystore $PREFIX.example.com.p12 \
    -file $PREFIX.example.com.csr

  # Tell exampleCA to sign the certificate.
  keytool -gencert -v \
    -alias exampleca \
    -keypass:env PW \
    -storepass:env PW \
    -keystore exampleca.p12 \
    -infile $PREFIX.example.com.csr \
    -outfile $PREFIX.example.com.crt \
    -ext KeyUsage:critical=$4 \
    -ext EKU=$5 \
    -ext SAN=$6 \
    -rfc \
    -validity 3650

  rm $PREFIX.example.com.csr

  # Tell $PREFIX.example.com.p12 it can trust exampleca as a signer.
  keytool -import -v \
    -alias exampleca \
    -file exampleca.crt \
    -keystore $PREFIX.example.com.p12 \
    -storetype PKCS12 \
    -storepass:env PW << EOF
yes
EOF

  # Import the signed certificate back into $PREFIX.example.com.p12
  keytool -import -v \
    -alias $PREFIX.example.com \
    -file $PREFIX.example.com.crt \
    -keystore $PREFIX.example.com.p12 \
    -storetype PKCS12 \
    -storepass:env PW

}

function exportRSAPrivateKeyAsPem {
  PREFIX=$1
  # Export the private key as a PEM. This export adds some extra PKCS#12 bag info.
  openssl pkcs12 -in $PREFIX.example.com.p12 -nodes -nocerts -out tmp.pem -passin pass:kLnCu3rboe
  # A second pass extracts the PKCS#12 bag information and produces a clean PEM file.
  openssl rsa -in tmp.pem -out $PREFIX.example.com.pem
  rm tmp.pem
}

function createExampleRSAKeySet() {
  PREFIX=$1
  EKU=$2
  SAN=$3
  createKeySet $PREFIX \
    RSA \
    2048 \
    "digitalSignature,keyEncipherment" \
    $EKU \
    $SAN
  exportRSAPrivateKeyAsPem $PREFIX
}


function createExampleECKeySet() {
  PREFIX=$1
  EKU=$2
  SAN=$3
  createKeySet $PREFIX \
    EC \
    256 \
    "digitalSignature,keyEncipherment" \
    $EKU \
    $SAN
}

