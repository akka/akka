# Documents how truststore and keystore were created

all: truststore keystore

truststore: domain.crt
	keytool -importcert -file domain.crt -keystore truststore -deststorepass changeme

keystore: domain.crt domain.key
	openssl pkcs12 -export -inkey domain.key -passin pass:changeme -in domain.crt -out keystore -passout pass:changeme

domain.crt: domain.csr domain.key domain.cnf
	openssl x509 -req -in domain.csr -sha256 -extfile domain.cnf -out domain.crt -extensions SAN -signkey domain.key

domain.cnf:
	cat /etc/ssl/openssl.cnf > domain.cnf
	echo "[SAN]" >> domain.cnf
	echo "subjectAltName=DNS:localhost" >> domain.cnf

domain.csr: domain.cnf
	openssl req -new -newkey rsa:2048 -keyout domain.key -subj "/C=ZA/ST=web/O=Lightbend/CN=akka-remote" -reqexts SAN -config domain.cnf -out domain.csr -passout pass:changeme

.PHONY: clean
clean:
	rm domain.key domain.crt domain.csr keystore truststore domain.cnf
