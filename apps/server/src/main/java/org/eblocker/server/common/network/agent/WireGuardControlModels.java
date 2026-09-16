// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.network.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** The complete public control protocol. Secret-bearing fields have no representation. */
public final class WireGuardControlModels {
    private WireGuardControlModels() { }
    private static final Set<String> PHASES = Set.of("imported", "prepared", "applying", "active", "removing",
            "rolling_back", "disconnected", "failed", "cancelled", "recovery_required");

    public record Profiles(@JsonProperty(required = true) List<Summary> profiles) {
        public Profiles {
            profiles = List.copyOf(profiles);
            if (profiles.stream().map(Summary::profileId).distinct().count() != profiles.size()) invalid();
        }
    }
    public record Summary(@JsonProperty(required = true) String profileId,
                          @JsonProperty(required = true) String phase,
                          @JsonProperty(required = true) NetworkAgentModels.WireGuardPlan plan) {
        public Summary { id(profileId); WireGuardControlModels.phase(phase); WireGuardControlModels.plan(plan); }
    }
    public record Detail(@JsonProperty(required = true) String profileId,
                         @JsonProperty(required = true) String phase,
                         @JsonProperty(required = true) NetworkAgentModels.WireGuardPlan plan,
                         @JsonProperty(required = true) Runtime runtime) {
        public Detail {
            id(profileId); WireGuardControlModels.phase(phase); WireGuardControlModels.plan(plan);
            if (runtime != null && (!runtime.target.profileId.equals(profileId) || !runtime.phase.equals(phase)
                    || !runtime.plan.equals(plan))) invalid();
            if (runtime == null && !phase.equals("imported")) invalid();
        }
    }
    public record Deleted(@JsonProperty(required = true) String profileId,
                          @JsonProperty(required = true) boolean deleted) {
        public Deleted { id(profileId); if (!deleted) invalid(); }
    }
    public record Cancelled(@JsonProperty(required = true) String profileId,
                            @JsonProperty(required = true) boolean cancellationRequested) {
        public Cancelled { id(profileId); }
    }
    public record Target(@JsonProperty(required = true) String profileId,
                         @JsonProperty(required = true) String interfaceName,
                         @JsonProperty(required = true) String ownershipId) {
        public Target { id(profileId); owner(interfaceName, ownershipId); }
    }
    public record Owner(@JsonProperty(required = true) String interfaceName,
                        @JsonProperty(required = true) String ownershipId) {
        public Owner { owner(interfaceName, ownershipId); }
    }
    public record Runtime(@JsonProperty(required = true) int schemaVersion,
                          @JsonProperty(required = true) Target target,
                          @JsonProperty(required = true) String phase,
                          @JsonProperty(required = true) NetworkAgentModels.WireGuardPlan plan,
                          @JsonProperty(required = true) Observation observation,
                          String errorCode, Policy policy,
                          @JsonProperty(required = true) boolean killSwitchActive) {
        public Runtime {
            if (schemaVersion != 1 || target == null || observation == null) invalid();
            WireGuardControlModels.phase(phase); WireGuardControlModels.plan(plan);
            if (phase.equals("imported")) invalid();
            if (errorCode != null && !errorCode.matches("[a-z_]{1,64}")) invalid();
            if (policy != null && (!policy.owner.ownershipId.equals(target.ownershipId)
                    || !policy.owner.interfaceName.equals(target.interfaceName))) invalid();
            if (phase.equals("active") && (!observation.exists || !observation.owned || !observation.up)) invalid();
            if (killSwitchActive && (!phase.equals("active") || policy == null || observation.policy == null
                    || !observation.policy.verified(policy))) invalid();
        }
    }
    public record Observation(@JsonProperty(required = true) boolean exists,
                              @JsonProperty(required = true) boolean owned,
                              @JsonProperty(required = true) boolean up,
                              @JsonProperty(required = true) List<PeerObservation> peers,
                              Attestation policy) {
        public Observation { peers = List.copyOf(peers); }
    }
    public record PeerObservation(@JsonProperty(required = true) String publicKey,
                                  @JsonProperty(required = true) long lastHandshakeUnix,
                                  @JsonProperty(required = true) BigInteger receiveBytes,
                                  @JsonProperty(required = true) BigInteger transmitBytes) {
        public PeerObservation {
            text(publicKey); unsigned64(receiveBytes); unsigned64(transmitBytes);
            if (lastHandshakeUnix < 0) invalid();
        }
    }
    public record Endpoint(@JsonProperty(required = true) String address,
                           @JsonProperty(required = true) int port,
                           @JsonProperty(required = true) String interfaceName) {
        public Endpoint { text(address); text(interfaceName); if (port < 1 || port > 65535) invalid(); }
    }
    public record Policy(@JsonProperty(required = true) int version,
                         @JsonProperty(required = true) Owner owner,
                         @JsonProperty(required = true) long routeTable,
                         @JsonProperty(required = true) long firewallMark,
                         @JsonProperty(required = true) long mainRulePriority,
                         @JsonProperty(required = true) long tunnelRulePriority,
                         @JsonProperty(required = true) String nftTable,
                         @JsonProperty(required = true) List<String> allowedIPs,
                         @JsonProperty(required = true) List<Endpoint> endpoints,
                         @JsonProperty(required = true) List<String> localNetworks,
                         @JsonProperty(required = true) List<String> localInterfaces,
                         @JsonProperty(required = true) boolean ipv4Default,
                         @JsonProperty(required = true) boolean ipv6Default,
                         @JsonProperty(required = true) String digest) {
        public Policy {
            if (version != 1 || owner == null || !(ipv4Default || ipv6Default)) invalid();
            unsigned32(routeTable); unsigned32(firewallMark); unsigned32(mainRulePriority); unsigned32(tunnelRulePriority);
            if (!nftTable.equals("ebwg_" + owner.ownershipId)) invalid();
            WireGuardControlModels.digest(digest);
            allowedIPs = List.copyOf(allowedIPs); endpoints = List.copyOf(endpoints);
            localNetworks = List.copyOf(localNetworks); localInterfaces = List.copyOf(localInterfaces);
        }
    }
    public record Attestation(@JsonProperty(required = true) Owner owner,
                              @JsonProperty(required = true) String digest,
                              @JsonProperty(required = true) boolean routingVerified,
                              @JsonProperty(required = true) boolean firewallVerified,
                              @JsonProperty(required = true) boolean markVerified,
                              @JsonProperty(required = true) boolean endpointsVerified,
                              @JsonProperty(required = true) boolean ipv4PoliciesVerified,
                              @JsonProperty(required = true) boolean ipv6PoliciesVerified,
                              @JsonProperty(required = true) boolean killSwitchActive) {
        public Attestation { if (owner == null) invalid(); WireGuardControlModels.digest(digest); }
        boolean verified(Policy plan) {
            return owner.equals(plan.owner) && digest.equals(plan.digest) && routingVerified && firewallVerified
                    && markVerified && endpointsVerified && ipv4PoliciesVerified && ipv6PoliciesVerified && killSwitchActive;
        }
    }
    static void id(String id) { if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}")) invalid(); }
    private static void phase(String phase) { if (!PHASES.contains(phase)) invalid(); }
    private static void plan(NetworkAgentModels.WireGuardPlan plan) {
        if (plan == null || plan.applied() || plan.killSwitchActive()) invalid();
    }
    private static void owner(String name, String ownership) {
        if (ownership == null || !ownership.matches("[a-f0-9]{32}") || ! ("ebwg" + ownership.substring(0, 10)).equals(name)) invalid();
    }
    private static void digest(String digest) { if (digest == null || !digest.matches("[a-f0-9]{64}")) invalid(); }
    private static void text(String text) { if (text == null || text.isEmpty() || text.length() > 512) invalid(); }
    private static void unsigned32(long value) { if (value < 0 || value > 0xffff_ffffL) invalid(); }
    private static void unsigned64(BigInteger value) { if (value == null || value.signum() < 0 || value.bitLength() > 64) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("Invalid public WireGuard control state"); }
}
