"""Offline source contract check; Java tests additionally exercise real DI/wiring."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROUTES = ROOT / 'apps/server/src/main/java/org/eblocker/server/http/server/routes'


def without_comments(source):
    # Preserve string literals: route URIs may contain slashes or escaped quotes.
    tokens = r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/'
    return re.sub(tokens, lambda m: m[0] if m[0].startswith('"') else '', source, flags=re.S)


def declarations(source, fields):
    rows = []
    for m in re.finditer(r'server\s*\.\s*(uri|regex)\("([^"\n]+)",\s*(\w+)\)(.*?);', source, re.S):
        kind, path, controller, tail = m.groups()
        steps = []
        for step in re.finditer(r'\.(\w+)\(([^)]*)\)', tail):
            name, arguments = step.groups()
            values = [json.loads(a.strip()) if a.strip().startswith('"') else a.strip().rsplit('.', 1)[-1]
                      for a in arguments.split(',')] if arguments else []
            steps.append([name, *values])
        rows.append(dict(kind=kind, path=path, controller=fields[controller], steps=steps))
    return rows


class RouteContractTest(unittest.TestCase):
    def test_all_routes_preserve_order_and_security_contracts(self):
        files = {p.stem: without_comments(p.read_text()) for p in ROUTES.glob('*.java')}
        composition = files['EblockerRoutes']
        types = dict((name, kind) for kind, name in re.findall(r'private final (\w+) (\w+);', composition))
        actual = []
        called = set()
        for instance, method in re.findall(r'(\w+Routes)\.(\w+)\(server\);', composition):
            source = files[types[instance]]
            fields = dict((name, kind) for kind, name in re.findall(r'private final (\w+) (\w+);', source))
            body = re.search(r'void '+method+r'\(RestExpress server\) \{(.*?)^    \}', source, re.S | re.M)
            self.assertIsNotNone(body, method)
            key = (types[instance], method)
            self.assertNotIn(key, called)
            called.add(key)
            actual.extend(declarations(body[1], fields))
        actual.extend(declarations(composition, types))
        expected = json.loads((ROOT / 'apps/server/src/test/resources/contracts/http-routes.json').read_text())
        additions = json.loads((ROOT / 'apps/server/src/test/resources/contracts/http-routes-additions.json').read_text())
        self.assertEqual(expected, [row for row in actual if row not in additions],
                         'All original routes must retain their declaration and precedence')
        self.assertEqual(additions, [row for row in actual if row in additions],
                         'New routes require an explicit security contract and must occur exactly once')
        all_methods = {(name, method) for name, source in files.items()
                       for method in re.findall(r'void (\w+)\(RestExpress server\)', source)
                       if name != 'EblockerRoutes'}
        self.assertEqual(all_methods, called, 'Every feature registration must be called exactly once')
        self.assertEqual('/.*', actual[-1]['path'])


if __name__ == '__main__':
    unittest.main()
