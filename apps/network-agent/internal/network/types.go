// Package network exposes observed kernel state, never arbitrary commands.
package network

import "context"

type Provider interface {
	Interfaces(context.Context) ([]Interface, error)
	Routes(context.Context) ([]Route, error)
	WireGuard(context.Context) (WireGuardCapability, error)
}

type Interface struct {
	Index           int       `json:"index"`
	Name            string    `json:"name"`
	MTU             int       `json:"mtu"`
	HardwareAddress string    `json:"hardwareAddress,omitempty"`
	Up              bool      `json:"up"`
	Running         bool      `json:"running"`
	Loopback        bool      `json:"loopback"`
	Multicast       bool      `json:"multicast"`
	Addresses       []Address `json:"addresses"`
}

type Address struct {
	Prefix string `json:"prefix"`
	Family string `json:"family"`
	Scope  string `json:"scope"`
}

type Route struct {
	Family          string    `json:"family"`
	Destination     string    `json:"destination"`
	Source          string    `json:"source,omitempty"`
	Gateway         string    `json:"gateway,omitempty"`
	PreferredSource string    `json:"preferredSource,omitempty"`
	InterfaceIndex  uint32    `json:"interfaceIndex,omitempty"`
	Table           uint32    `json:"table"`
	Priority        uint32    `json:"priority"`
	Protocol        uint8     `json:"protocol"`
	Scope           uint8     `json:"scope"`
	Type            uint8     `json:"type"`
	Flags           uint32    `json:"flags"`
	NextHops        []NextHop `json:"nextHops,omitempty"`
}

type NextHop struct {
	InterfaceIndex uint32 `json:"interfaceIndex"`
	Gateway        string `json:"gateway,omitempty"`
	Weight         uint16 `json:"weight"`
	Flags          uint8  `json:"flags"`
}

type WireGuardCapability struct {
	KernelFamilyRegistered bool   `json:"kernelFamilyRegistered"`
	State                  string `json:"state"`
	Management             bool   `json:"management"`
	Reason                 string `json:"reason"`
}
