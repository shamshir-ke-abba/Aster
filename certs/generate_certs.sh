#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "Generating Aster Root CA..."
openssl req -x509 -new -nodes -newkey rsa:2048 -days 3650 \
  -keyout aster_ca.key -out aster_ca.crt \
  -subj "/C=IN/O=Aster/CN=Aster Root CA" \
  -addext "basicConstraints = critical, CA:TRUE" \
  -addext "keyUsage = critical, keyCertSign, cRLSign" \
  -addext "subjectKeyIdentifier = hash"

echo "Generating Server Key & CSR..."
openssl req -new -nodes -newkey rsa:2048 \
  -keyout aster_server.key -out aster_server.csr \
  -subj "/C=IN/O=Aster/CN=aster.local"

cat << 'CONF' > server_ext.cnf
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = aster.local
DNS.2 = localhost
IP.1 = 127.0.0.1
IP.2 = 192.168.240.1
IP.3 = 192.168.0.159
IP.4 = 192.168.0.114
CONF

echo "Signing Server Certificate with Aster Root CA..."
openssl x509 -req -in aster_server.csr -CA aster_ca.crt -CAkey aster_ca.key -CAcreateserial \
  -out aster_server.crt -days 3650 -extfile server_ext.cnf

echo "Creating PKCS12 Keystore for Ktor Backend (password: aster_keystore_secret)..."
openssl pkcs12 -export -in aster_server.crt -inkey aster_server.key -certfile aster_ca.crt \
  -out aster_server.p12 -name aster-server -password pass:aster_keystore_secret

echo "Verifying certificates..."
openssl x509 -in aster_server.crt -noout -text | grep -A 8 "Subject Alternative Name"

echo "Done!"
