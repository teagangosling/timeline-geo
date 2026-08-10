#!/bin/sh
set -e

# Use /app/tmp for sed temp files
export TMPDIR=/app/tmp

cp /etc/nginx/conf.d/default.conf.template /etc/nginx/conf.d/default.conf

CURRENT_UID=$(id -u)

if [ "$CURRENT_UID" = "0" ]; then
  NGINX_PORT=80
  echo "Running as root (UID 0) - using port 80"
else
  NGINX_PORT=8080
  echo "Running as non-root (UID $CURRENT_UID) - using port 8080"
fi

# Generate config.js
echo "window.VUE_APP_CONFIG = {" > /app/config/config.js
echo "  API_BASE_URL: '/api'," >> /app/config/config.js
echo "};" >> /app/config/config.js

# Detect Resolver
#
# The two resolvers are deliberately NOT the same. nginx treats a resolver list
# as a pool and spreads queries across it, so a public resolver in the BACKEND
# list NXDOMAINs the container name on whatever share of re-resolutions land on
# it -- intermittent "geopulse-backend could not be resolved (3: Host not found)"
# and a 502, every valid= interval. The backend name only ever exists in Docker's
# embedded DNS; keep the public fallback for OSM tiles, which are a real hostname.
if grep -q "127.0.0.11" /etc/resolv.conf; then
  OSM_DEFAULT_RESOLVER="127.0.0.11 8.8.8.8"
  BACKEND_DEFAULT_RESOLVER="127.0.0.11"
else
  SYSTEM_NAMESERVERS=$(grep '^nameserver' /etc/resolv.conf | awk '{if ($2 ~ ":") print "["$2"]"; else print $2}' | paste -sd " " -)
  OSM_DEFAULT_RESOLVER="${SYSTEM_NAMESERVERS:-8.8.8.8}"
  BACKEND_DEFAULT_RESOLVER="${SYSTEM_NAMESERVERS:-8.8.8.8}"
fi
: "${OSM_RESOLVER:=${OSM_DEFAULT_RESOLVER}}"
: "${BACKEND_RESOLVER:=${BACKEND_DEFAULT_RESOLVER}}"

# The /api/ block builds its upstream as $geopulse_backend$request_uri, so a
# trailing slash here would double up as //api/... Strip it.
BACKEND_URL="${GEOPULSE_BACKEND_URL:-http://geopulse-backend:8080}"
BACKEND_URL="${BACKEND_URL%/}"

# Replace placeholders
# We are modifying the fresh copy we just made from the template
sed -i "s|BACKEND_URL_PLACEHOLDER|${BACKEND_URL}|g" /etc/nginx/conf.d/default.conf
sed -i "s|BACKEND_RESOLVER_PLACEHOLDER|${BACKEND_RESOLVER}|g" /etc/nginx/conf.d/default.conf
sed -i "s|CLIENT_MAX_BODY_SIZE_PLACEHOLDER|${CLIENT_MAX_BODY_SIZE:-200M}|g" /etc/nginx/conf.d/default.conf
sed -i "s|OSM_RESOLVER_PLACEHOLDER|${OSM_RESOLVER}|g" /etc/nginx/conf.d/default.conf
sed -i "s|NGINX_PORT_PLACEHOLDER|${NGINX_PORT}|g" /etc/nginx/conf.d/default.conf

echo "Starting Nginx on port ${NGINX_PORT}..."

# Start Nginx
exec nginx -g "daemon off;"