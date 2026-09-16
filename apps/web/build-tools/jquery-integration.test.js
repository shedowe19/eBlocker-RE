'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {JSDOM} = require('jsdom');
const babel = require('@babel/core');

function createWindow(t) {
    const dom = new JSDOM('<!doctype html><html><body></body></html>', {
        url: 'https://eblocker.test/', runScripts: 'outside-only'
    });
    t.after(() => dom.window.close());
    Object.defineProperty(dom.window.navigator, 'maxTouchPoints', {value: 1});
    return dom.window;
}

function load(window, module) {
    window.eval(fs.readFileSync(require.resolve(module), 'utf8'));
}

test('jQuery 4 supports Angular models and the dashboard sortable widget on touch devices', t => {
    const window = createWindow(t);
    [
        'jquery', 'angular/angular.js', 'jquery-ui/ui/data', 'jquery-ui/ui/widget',
        'jquery-ui/ui/scroll-parent', 'jquery-ui/ui/widgets/mouse', 'jquery-ui/ui/widgets/sortable',
        '@rwap/jquery-ui-touch-punch/jquery.ui.touch-punch.js', 'angular-ui-sortable'
    ].forEach(module => load(window, module));
    assert.equal(window.jQuery.fn.jquery, '4.0.0');
    const injector = window.angular.injector(['ng', 'ui.sortable']);
    const scope = injector.get('$rootScope').$new();
    scope.items = ['Alpha', 'Beta'];
    const element = injector.get('$compile')(
        '<ul ui-sortable ng-model="items"><li ng-repeat="item in items">{{item}}</li></ul>'
    )(scope);
    window.jQuery(window.document.body).append(element);
    scope.$digest();
    assert.ok(element.sortable('instance'));
    assert.equal(element.children().text(), 'AlphaBeta');
    scope.items.reverse();
    scope.$digest();
    assert.equal(element.children().text(), 'BetaAlpha');
    scope.$destroy();
    element.remove();
});

for (const name of ['anon', 'cloaking', 'filters', 'online-time', 'user']) {
    test(`destroying the ${name} dropdown preserves other document click listeners`, t => {
        const window = createWindow(t);
        load(window, 'jquery');
        load(window, 'angular/angular.js');
        const source = fs.readFileSync(path.join(__dirname,
            `../src/controlbar/app/directives/dropdown-${name}.directive.js`), 'utf8');
        window.exports = {};
        window.eval(babel.transformSync(source, {presets: ['env']}).code);
        const $document = window.jQuery(window.document);
        const scope = window.angular.injector(['ng']).get('$rootScope').$new();
        let unrelatedClicks = 0;
        $document.on('click.otherFeature', () => unrelatedClicks++);
        const directive = window.exports.default($document, {isDropdownOpen() {}});
        directive.link(scope, window.jQuery('<div></div>'));
        $document.trigger('click');
        assert.equal(unrelatedClicks, 1);
        scope.$destroy();
        $document.trigger('click');
        assert.equal(unrelatedClicks, 2);
        $document.off('.otherFeature');
    });
}
