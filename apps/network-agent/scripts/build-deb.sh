#!/bin/sh
# Build only: does not install packages, create users, or modify host networking.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
architecture=${1:-amd64}
version=${2:-4.0.3+next1}
case "$architecture" in amd64|arm64) ;; *) echo 'Architecture must be amd64 or arm64' >&2; exit 2;; esac
case "$version" in ''|*[!a-zA-Z0-9.+:~_-]*) echo 'Invalid Debian version' >&2; exit 2;; esac
case "$version" in [0-9]*) ;; *) echo 'Debian version must start with a digit' >&2; exit 2;; esac
dpkg --validate-version "$version"
command -v go >/dev/null
command -v dpkg-deb >/dev/null
command -v python3 >/dev/null
output_dir="$project_dir/target"
mkdir -p "$output_dir"
stage=$(mktemp -d "$output_dir/package-$architecture.XXXXXXXX")
trap 'rm -rf -- "$stage"' EXIT HUP INT TERM
install -d "$stage/DEBIAN" "$stage/usr/lib/eblocker-network-agent" \
    "$stage/usr/lib/systemd/system/icapserver.service.d" \
    "$stage/usr/share/doc/eblocker-network-agent"
(
    cd "$project_dir"
    CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" go build -trimpath -buildvcs=false \
        -ldflags "-s -w -X main.version=$version" \
        -o "$stage/usr/lib/eblocker-network-agent/eblocker-network-agent" ./cmd/eblocker-network-agent
    CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" go build -trimpath -buildvcs=false \
        -ldflags "-s -w -X main.version=$version" \
        -o "$stage/usr/lib/eblocker-network-agent/eblocker-wireguard" ./cmd/eblocker-wireguard
    CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" go build -trimpath -buildvcs=false \
        -ldflags "-s -w -X main.version=$version" \
        -o "$stage/usr/lib/eblocker-network-agent/eblocker-network-control" ./cmd/eblocker-network-control
    go mod download all
    python3 scripts/collect-licenses.py "$stage/usr/share/doc/eblocker-network-agent/third-party"
)
install -m 0644 "$project_dir/packaging/eblocker-network-agent.service" "$stage/usr/lib/systemd/system/"
install -m 0644 "$project_dir/cmd/eblocker-network-control/eblocker-network-control.service" "$stage/usr/lib/systemd/system/"
install -m 0644 "$project_dir/packaging/icapserver-network-agent.conf" "$stage/usr/lib/systemd/system/icapserver.service.d/network-agent.conf"
install -m 0644 "$project_dir/README.md" "$stage/usr/share/doc/eblocker-network-agent/README.md"
install -m 0644 "$project_dir/NATIVE_RUNTIME.md" "$stage/usr/share/doc/eblocker-network-agent/NATIVE_RUNTIME.md"
install -m 0644 "$project_dir/internal/controlapi/README.md" "$stage/usr/share/doc/eblocker-network-agent/CONTROL_API.md"
install -m 0644 "$project_dir/../../LICENSE.md" "$stage/usr/share/doc/eblocker-network-agent/copyright"
for script in postinst prerm postrm; do install -m 0755 "$project_dir/packaging/$script" "$stage/DEBIAN/$script"; done
cat > "$stage/DEBIAN/control" <<CONTROL
Package: eblocker-network-agent
Version: $version
Architecture: $architecture
Section: net
Priority: optional
Depends: adduser, systemd (>= 247)
Maintainer: eBlocker Community <community@eblocker.org>
Description: eBlocker network observation and explicit WireGuard runtime tools
 Local Unix-socket API for interface and route observations and redacted
 WireGuard profile plans. Separate explicitly enabled control tools manage
 owned split/full WireGuard tunnels; the observation service remains read-only.
CONTROL
dpkg-deb --root-owner-group --build "$stage" "$output_dir/eblocker-network-agent_${version}_${architecture}.deb"
