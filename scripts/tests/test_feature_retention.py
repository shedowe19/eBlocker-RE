"""Removal gate for existing screens; does not claim browser or appliance parity."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SETTINGS = ROOT / 'apps/web/src/settings'


class FeatureRetentionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.states = json.loads((ROOT / 'apps/web/build-tools/contracts/settings-states.json').read_text())['states']
        cls.catalog = json.loads((ROOT / 'apps/console/src/features/catalog/catalog.json').read_text())

    def test_every_existing_state_has_exactly_one_feature_owner(self):
        owned = [state for feature in self.catalog for state in feature['states']]
        self.assertCountEqual([state['name'] for state in self.states], owned)
        self.assertEqual(len(owned), len(set(owned)), 'A feature must not silently disappear into another owner')

    def test_all_original_components_and_their_templates_remain_available(self):
        source = (SETTINGS / 'app/settings.module.js').read_text()
        components = dict(re.findall(r"\.component\('([^']+)',\s*(\w+)\)", source))
        imports = dict(re.findall(r"import (\w+) from '([^']+)';", source))
        for state in self.states:
            component = state.get('component')
            if not component:
                continue
            with self.subTest(state=state['name']):
                self.assertIn(component, components)
                self.assertIn(components[component], imports)
                # Imports such as foo.component contain a suffix that is part of the basename.
                implementation = Path(str(SETTINGS / 'app' / imports[components[component]]) + '.js')
                self.assertTrue(implementation.is_file(), implementation)
                for template in re.findall(r"templateUrl:\s*'([^']+)'", implementation.read_text()):
                    self.assertTrue((SETTINGS / template).is_file(), template)

    def test_catalog_links_are_local_page_navigation_not_api_operations(self):
        ids = [feature['id'] for feature in self.catalog]
        self.assertEqual(len(ids), len(set(ids)))
        for feature in self.catalog:
            with self.subTest(feature=feature['id']):
                self.assertTrue(feature['href'].startswith('/settings/#!/'))
                self.assertNotRegex(feature['href'], r'[\\<>]|\.\.|/api/')
                self.assertEqual({'de', 'en'}, set(feature['title']))
                self.assertTrue(all(feature['title'].values()))
                if feature['modern']:
                    self.assertRegex(feature['modern'], r'^/[a-z-]+$')

    def test_catalog_links_resolve_to_existing_settings_urls(self):
        states = {state['name']: state for state in self.states}

        def full_url(name):
            state = states['app' if name == 'PARENT' else name]
            parent = full_url(state['parent']) if state.get('parent') else ''
            return parent + state.get('url', '').split('?')[0]

        paths = {re.sub(r':\w+', '', full_url(state['name'])) for state in self.states}
        for feature in self.catalog:
            self.assertIn(feature['href'].removeprefix('/settings/#!'), paths, feature['id'])

    def test_existing_application_entries_and_maven_web_package_are_retained(self):
        for app in ('settings', 'dashboard', 'controlbar', 'setup', 'advice'):
            self.assertTrue((ROOT / 'apps/web/src' / app / 'index.html').is_file(), app)
        reactor = (ROOT / 'pom.xml').read_text()
        self.assertIn('<module>apps/web</module>', reactor)
        self.assertIn('<module>apps/console</module>', reactor)


if __name__ == '__main__':
    unittest.main()
