#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v javac >/dev/null 2>&1 && [[ -x /workspace/build-tools-cache/jdk/bin/javac ]]; then
  export JAVA_HOME=/workspace/build-tools-cache/jdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi
args=()
# Gradle does not automatically use HTTP_PROXY/HTTPS_PROXY. Preserve the session route and TLS checks.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  mapfile -t proxy_parts < <(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
print(u.hostname or '')
print(u.port or 8080)
PY
)
  args+=("-Dhttps.proxyHost=${proxy_parts[0]}" "-Dhttps.proxyPort=${proxy_parts[1]}" "-Dhttp.proxyHost=${proxy_parts[0]}" "-Dhttp.proxyPort=${proxy_parts[1]}" '-Dhttp.nonProxyHosts=localhost|127.*')
fi
if [[ -f "${CODEX_PROXY_CERT:-}" ]]; then
  trust_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/lumenkot"
  mkdir -p "$trust_dir"
  java_base="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
  cp -L "$java_base/lib/security/cacerts" "$trust_dir/cacerts"
  keytool -importcert -noprompt -alias lumenkot-session-ca -file "$CODEX_PROXY_CERT" -keystore "$trust_dir/cacerts" -storepass changeit >/dev/null 2>&1
  args+=("-Djavax.net.ssl.trustStore=$trust_dir/cacerts" '-Djavax.net.ssl.trustStorePassword=changeit')
fi
if [[ $# -eq 0 ]]; then set -- :app:assembleRelease :app:testDebugUnitTest :app:lintDebug; fi
./gradlew "${args[@]}" "$@"
