.PHONY: help check test web console verify dns native persistence wireguard network-agent network-agent-integration network-agent-package network-control-integration
help:
	@echo 'check   source layout and HTTP contracts'
	@echo 'test    Java applications and their in-tree dependencies'
	@echo 'web     install locked UI dependencies, build, browser tests'
	@echo 'console install locked React console dependencies, test and build'
	@echo 'verify  complete Maven reactor, tests and Debian packages'
	@echo 'dns     Go DNS unit tests'
	@echo 'persistence                test and package the offline SQLite migration CLI'
	@echo 'wireguard                  vet and race-test WireGuard profile parsing'
	@echo 'network-agent              vet and race-test observation and privileged control services'
	@echo 'network-agent-integration  also test real Linux Unix sockets and Netlink'
	@echo 'network-control-integration test the private Unix control socket on an isolated Linux runner'
	@echo 'network-agent-package      build amd64 and arm64 Debian packages after unit tests'
	@echo 'native  compile C network tools (libpcap, libnet, hiredis required)'
check:
	python3 -m unittest discover -s scripts/tests -v
test:
	python3 scripts/workspace.py test
web:
	python3 scripts/workspace.py web
console:
	cd apps/console && npm ci && npm run check
verify:
	python3 scripts/workspace.py verify
dns:
	cd platform/dns/coredns && go test ./...
	cd platform/dns/dynamic && go test ./...
wireguard:
	cd libs/wireguard && go vet ./... && go test -race ./...
persistence:
	./mvnw -B -ntp -pl libs/persistence -am package
network-agent:
	cd apps/network-agent && go vet ./... && go test -race ./...
network-agent-integration:
	cd apps/network-agent && EBLOCKER_AGENT_INTEGRATION=1 go test -race ./...
network-control-integration:
	cd apps/network-agent && EBLOCKER_CONTROL_SOCKET_TEST=1 go test -race -tags=controlintegration ./internal/controlapi
network-agent-package: wireguard network-agent
	apps/network-agent/scripts/build-deb.sh amd64
	apps/network-agent/scripts/build-deb.sh arm64
native:
	$(MAKE) -C platform/native/network-tools
