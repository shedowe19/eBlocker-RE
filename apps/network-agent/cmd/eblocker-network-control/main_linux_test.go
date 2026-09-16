package main

import "testing"

func TestExplicitWriteAcknowledgementAndPeerIdentityRequired(t *testing.T) {
	for _, args := range [][]string{nil, {"--enable-native-writes"}, {"--peer-user=icapd", "--peer-group=icapd"}, {"--enable-native-writes", "--peer-user=icapd"}, {"--enable-native-writes", "--peer-user=icapd", "--peer-group=icapd", "unexpected"}} {
		if _, err := parseOptions(args); err == nil {
			t.Fatal("implicit or ambiguous write service accepted")
		}
	}
	options, err := parseOptions([]string{"--enable-native-writes", "--peer-user=icapd", "--peer-group=icapd"})
	if err != nil || !options.writes || options.socket != "/run/eblocker-network-control/control.sock" {
		t.Fatal("explicit service options rejected", err)
	}
}
