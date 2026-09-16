# ADR 0001: Separate HTTP lifecycle from feature route declarations

Status: accepted for this working branch.

The original server combines TLS/listener lifecycle with 360 fluent route
declarations and controller injection. This makes a routing change inseparable
from transport review and hides security-sensitive names/flags among thousands
of lines.

Move declarations into nine feature modules and a single ordered composition
root. Preserve route paths, methods, actions, controller identities, flags,
serialization modes and precedence. Keep the original final catch-all. Validate
with a frozen contract derived from the original commit, an offline source check
and an executable Guice/Mockito registration test.

Maven artifact names, package installation paths, controller behavior, Redis data,
network configuration and public URLs do not change. The extra composition layer
is explicit and temporary at the transport boundary; it is not the new control
plane. Remove it together with RestExpress only after replacement API and security
contracts pass. Track that removal in phase R13, owned by the HTTP/API migration.
