package wgkernel

import (
	"context"
	"net/netip"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
)

// These diagnostics are restricted to the disposable native tests and their
// fixed test keys. Production manager errors remain redacted. Only failed
// operations are logged, never key-bearing requests or kernel responses.
func nativeTestBackend(t *testing.T) *Backend {
	return &Backend{kernel: diagnosticKernel{nativeKernel{}, t}, policy: diagnosticFullKernel{nativeFullKernel{}, t}}
}

func reportNativeError(t *testing.T, operation string, err error) error {
	t.Helper()
	if err != nil {
		t.Logf("native %s failed: %v", operation, err)
	}
	return err
}

type diagnosticKernel struct {
	nativeKernel
	t *testing.T
}

func (d diagnosticKernel) create(ctx context.Context, name, alias string, mtu int) (link, error) {
	item, err := d.nativeKernel.create(ctx, name, alias, mtu)
	return item, reportNativeError(d.t, "create", err)
}
func (d diagnosticKernel) configure(ctx context.Context, item link, data []byte) error {
	return reportNativeError(d.t, "configure", d.nativeKernel.configure(ctx, item, data))
}
func (d diagnosticKernel) address(ctx context.Context, item link, address netip.Prefix) error {
	return reportNativeError(d.t, "address "+address.String(), d.nativeKernel.address(ctx, item, address))
}
func (d diagnosticKernel) route(ctx context.Context, item link, prefix netip.Prefix) error {
	return reportNativeError(d.t, "route "+prefix.String(), d.nativeKernel.route(ctx, item, prefix))
}
func (d diagnosticKernel) up(ctx context.Context, item link) error {
	return reportNativeError(d.t, "up", d.nativeKernel.up(ctx, item))
}
func (d diagnosticKernel) peers(ctx context.Context, item link) ([]manager.PeerObservation, error) {
	peers, err := d.nativeKernel.peers(ctx, item)
	return peers, reportNativeError(d.t, "peers", err)
}
func (d diagnosticKernel) remove(ctx context.Context, item link) error {
	return reportNativeError(d.t, "remove", d.nativeKernel.remove(ctx, item))
}

type diagnosticFullKernel struct {
	nativeFullKernel
	t *testing.T
}

func (d diagnosticFullKernel) installGuard(ctx context.Context, p policy.Plan) error {
	return reportNativeError(d.t, "install guard", d.nativeFullKernel.installGuard(ctx, p))
}
func (d diagnosticFullKernel) guard(ctx context.Context, p policy.Plan) (bool, bool, error) {
	exists, verified, err := d.nativeFullKernel.guard(ctx, p)
	if err == nil && exists && !verified {
		d.t.Log("native guard exists but does not match expected rules")
	}
	return exists, verified, reportNativeError(d.t, "guard readback", err)
}
func (d diagnosticFullKernel) installRouting(ctx context.Context, item link, p policy.Plan) error {
	return reportNativeError(d.t, "install routing", d.nativeFullKernel.installRouting(ctx, item, p))
}
func (d diagnosticFullKernel) routing(ctx context.Context, item link, p policy.Plan) (bool, bool, error) {
	v4, v6, err := d.nativeFullKernel.routing(ctx, item, p)
	return v4, v6, reportNativeError(d.t, "routing readback", err)
}
func (d diagnosticFullKernel) mark(ctx context.Context, item link) (uint32, error) {
	mark, err := d.nativeFullKernel.mark(ctx, item)
	return mark, reportNativeError(d.t, "mark readback", err)
}
func (d diagnosticFullKernel) endpoints(ctx context.Context, p policy.Plan) (bool, error) {
	valid, err := d.nativeFullKernel.endpoints(ctx, p)
	return valid, reportNativeError(d.t, "endpoints readback", err)
}
func (d diagnosticFullKernel) removeRouting(ctx context.Context, item link, p policy.Plan) error {
	return reportNativeError(d.t, "remove routing", d.nativeFullKernel.removeRouting(ctx, item, p))
}
func (d diagnosticFullKernel) removeGuard(ctx context.Context, p policy.Plan) error {
	return reportNativeError(d.t, "remove guard", d.nativeFullKernel.removeGuard(ctx, p))
}
